namespace KaderoAgent.Ipc;

using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

/// <summary>
/// Named Pipe server running inside Windows Service.
/// Accepts connections from Tray app, responds with agent status,
/// handles reconnect/update_settings commands.
/// </summary>
public class PipeServer : BackgroundService
{
    private readonly IStatusProvider _statusProvider;
    private readonly ICommandExecutor _commandExecutor;
    private readonly ILogger<PipeServer> _logger;
    private readonly JsonSerializerOptions _jsonOptions;

    public PipeServer(IStatusProvider statusProvider, ICommandExecutor commandExecutor, ILogger<PipeServer> logger)
    {
        _statusProvider = statusProvider;
        _commandExecutor = commandExecutor;
        _logger = logger;
        _jsonOptions = new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
            DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
        };
    }

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        _logger.LogInformation("PipeServer starting on pipe: {PipeName}", PipeProtocol.PipeName);

        while (!ct.IsCancellationRequested)
        {
            try
            {
                // Create pipe with security: SYSTEM + Admins = FullControl, AuthenticatedUsers = ReadWrite
                var pipeSecurity = new PipeSecurity();
                pipeSecurity.AddAccessRule(new PipeAccessRule(
                    new System.Security.Principal.SecurityIdentifier(
                        System.Security.Principal.WellKnownSidType.LocalSystemSid, null),
                    PipeAccessRights.FullControl,
                    System.Security.AccessControl.AccessControlType.Allow));
                pipeSecurity.AddAccessRule(new PipeAccessRule(
                    new System.Security.Principal.SecurityIdentifier(
                        System.Security.Principal.WellKnownSidType.BuiltinAdministratorsSid, null),
                    PipeAccessRights.FullControl,
                    System.Security.AccessControl.AccessControlType.Allow));
                pipeSecurity.AddAccessRule(new PipeAccessRule(
                    new System.Security.Principal.SecurityIdentifier(
                        System.Security.Principal.WellKnownSidType.AuthenticatedUserSid, null),
                    PipeAccessRights.ReadWrite,
                    System.Security.AccessControl.AccessControlType.Allow));

                using var server = NamedPipeServerStreamAcl.Create(
                    PipeProtocol.PipeName,
                    PipeDirection.InOut,
                    5, // maxNumberOfServerInstances (multiple RDP users)
                    PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous,
                    0, 0, pipeSecurity);

                await server.WaitForConnectionAsync(ct);
                _logger.LogDebug("Pipe client connected");

                // Handle in background, allow next connection
                _ = HandleClientAsync(server, ct);
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                _logger.LogError(ex, "PipeServer error, restarting listener");
                await Task.Delay(1000, ct);
            }
        }
    }

    private async Task HandleClientAsync(NamedPipeServerStream server, CancellationToken ct)
    {
        try
        {
            using var reader = new StreamReader(server, Encoding.UTF8);
            using var writer = new StreamWriter(server, Encoding.UTF8) { AutoFlush = true };

            while (server.IsConnected && !ct.IsCancellationRequested)
            {
                var line = await reader.ReadLineAsync(ct);
                if (line == null) break; // client disconnected

                PipeResponse response;
                try
                {
                    var request = JsonSerializer.Deserialize<PipeRequest>(line, _jsonOptions);
                    response = await ProcessRequestAsync(request!, ct);
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, "Pipe request processing error");
                    response = new PipeResponse { Success = false, Error = "Operation failed. Check service logs." };
                }

                var json = JsonSerializer.Serialize(response, _jsonOptions);
                await writer.WriteLineAsync(json);
            }
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Pipe client disconnected");
        }
        finally
        {
            if (server.IsConnected) server.Disconnect();
        }
    }

    private async Task<PipeResponse> ProcessRequestAsync(PipeRequest request, CancellationToken ct)
    {
        switch (request.Command)
        {
            case "get_status":
                return new PipeResponse { Success = true, Status = _statusProvider.GetCurrentStatus() };

            case "reconnect":
                var serverUrl = request.Params?.GetValueOrDefault("server_url");
                var token = request.Params?.GetValueOrDefault("token");
                var result = await _commandExecutor.ReconnectAsync(serverUrl, token, ct);
                return new PipeResponse { Success = result, Status = _statusProvider.GetCurrentStatus() };

            case "restart_service":
                // Removed: restart via pipe is a DoS risk. Use sc.exe with UAC elevation instead.
                return new PipeResponse { Success = false, Error = "Restart service via sc.exe (requires elevation)" };

            default:
                return new PipeResponse { Success = false, Error = $"Unknown command: {request.Command}" };
        }
    }
}
