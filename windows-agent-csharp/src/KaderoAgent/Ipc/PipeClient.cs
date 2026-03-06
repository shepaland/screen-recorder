namespace KaderoAgent.Ipc;

using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

/// <summary>
/// Named Pipe client for Tray application.
/// Connects to PipeServer running in Windows Service.
/// Thread-safe: uses SemaphoreSlim to serialize pipe operations.
/// All IO operations have timeouts to prevent STA message pump starvation.
/// </summary>
public class PipeClient : IDisposable
{
    private NamedPipeClientStream? _pipe;
    private StreamReader? _reader;
    private StreamWriter? _writer;
    private readonly JsonSerializerOptions _jsonOptions;
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private static readonly TimeSpan SendTimeout = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan LockTimeout = TimeSpan.FromSeconds(3);

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
            _pipe = new NamedPipeClientStream(".", PipeProtocol.PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
            await _pipe.ConnectAsync(timeoutMs);
            _reader = new StreamReader(_pipe, Encoding.UTF8);
            _writer = new StreamWriter(_pipe, Encoding.UTF8) { AutoFlush = true };
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
        if (!IsConnected) return null;

        // Serialize pipe access — prevent concurrent read/write on same stream
        if (!await _sendLock.WaitAsync(LockTimeout)) return null;
        try
        {
            using var cts = new CancellationTokenSource(SendTimeout);
            var json = JsonSerializer.Serialize(request, _jsonOptions);
            await _writer!.WriteLineAsync(json.AsMemory(), cts.Token);
            var responseLine = await _reader!.ReadLineAsync(cts.Token);
            if (responseLine == null) return null;
            return JsonSerializer.Deserialize<PipeResponse>(responseLine, _jsonOptions);
        }
        catch (OperationCanceledException)
        {
            // Timeout — pipe is stuck, disconnect and let next poll reconnect
            Disconnect();
            return null;
        }
        catch
        {
            Disconnect();
            return null;
        }
        finally
        {
            _sendLock.Release();
        }
    }

    public async Task<AgentStatus?> GetStatusAsync()
    {
        var response = await SendAsync(new PipeRequest { Command = "get_status" });
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
        return await SendAsync(request);
    }

    public void Disconnect()
    {
        _reader?.Dispose(); _reader = null;
        _writer?.Dispose(); _writer = null;
        _pipe?.Dispose(); _pipe = null;
    }

    public void Dispose()
    {
        Disconnect();
        _sendLock.Dispose();
    }
}
