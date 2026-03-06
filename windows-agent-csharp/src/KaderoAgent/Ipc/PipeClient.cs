namespace KaderoAgent.Ipc;

using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

/// <summary>
/// Named Pipe client for Tray application.
/// Connects to PipeServer running in Windows Service.
///
/// IMPORTANT: This class must be called from thread pool threads ONLY.
/// Never call from the STA/UI thread — use ThreadPool.QueueUserWorkItem or
/// System.Threading.Timer and marshal results back via BeginInvoke.
/// All async methods use ConfigureAwait(false) as defense-in-depth.
/// </summary>
public class PipeClient : IDisposable
{
    private NamedPipeClientStream? _pipe;
    private StreamReader? _reader;
    private StreamWriter? _writer;
    private readonly JsonSerializerOptions _jsonOptions;
    private readonly object _pipeLock = new();
    private static readonly TimeSpan IoTimeout = TimeSpan.FromSeconds(5);

    public bool IsConnected => _pipe?.IsConnected == true;

    public PipeClient()
    {
        _jsonOptions = new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
            DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
        };
    }

    public async Task<bool> ConnectAsync(int timeoutMs = 3000)
    {
        try
        {
            Disconnect();
            var pipe = new NamedPipeClientStream(".", PipeProtocol.PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
            await pipe.ConnectAsync(timeoutMs).ConfigureAwait(false);
            var reader = new StreamReader(pipe, Encoding.UTF8);
            var writer = new StreamWriter(pipe, Encoding.UTF8) { AutoFlush = true };

            lock (_pipeLock)
            {
                _pipe = pipe;
                _reader = reader;
                _writer = writer;
            }
            return true;
        }
        catch
        {
            Disconnect();
            return false;
        }
    }

    public async Task<PipeResponse?> SendAsync(PipeRequest request)
    {
        StreamReader? reader;
        StreamWriter? writer;

        lock (_pipeLock)
        {
            if (_pipe == null || !_pipe.IsConnected) return null;
            reader = _reader;
            writer = _writer;
        }

        if (reader == null || writer == null) return null;

        try
        {
            using var cts = new CancellationTokenSource(IoTimeout);
            var json = JsonSerializer.Serialize(request, _jsonOptions);
            await writer.WriteLineAsync(json.AsMemory(), cts.Token).ConfigureAwait(false);
            var responseLine = await reader.ReadLineAsync(cts.Token).ConfigureAwait(false);
            if (responseLine == null) return null;
            return JsonSerializer.Deserialize<PipeResponse>(responseLine, _jsonOptions);
        }
        catch (OperationCanceledException)
        {
            Disconnect();
            return null;
        }
        catch
        {
            Disconnect();
            return null;
        }
    }

    public async Task<AgentStatus?> GetStatusAsync()
    {
        var response = await SendAsync(new PipeRequest { Command = "get_status" }).ConfigureAwait(false);
        return response?.Status;
    }

    public async Task<PipeResponse?> ReconnectAsync(string? serverUrl, string? token)
    {
        var request = new PipeRequest
        {
            Command = "reconnect",
            Params = new Dictionary<string, string>()
        };
        if (!string.IsNullOrEmpty(serverUrl)) request.Params["server_url"] = serverUrl;
        if (!string.IsNullOrEmpty(token)) request.Params["token"] = token;
        return await SendAsync(request).ConfigureAwait(false);
    }

    public void Disconnect()
    {
        lock (_pipeLock)
        {
            _reader?.Dispose(); _reader = null;
            _writer?.Dispose(); _writer = null;
            _pipe?.Dispose(); _pipe = null;
        }
    }

    public void Dispose() => Disconnect();
}
