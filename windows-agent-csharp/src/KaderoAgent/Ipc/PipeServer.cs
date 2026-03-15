namespace KaderoAgent.Ipc;

using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using KaderoAgent.Audit;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

/// <summary>
/// Named Pipe server running inside Windows Service.
/// Accepts connections from Tray app, responds with agent status,
/// handles reconnect/update_settings/report_focus_intervals commands.
/// </summary>
public class PipeServer : BackgroundService
{
    private readonly IStatusProvider _statusProvider;
    private readonly ICommandExecutor _commandExecutor;
    private readonly FocusIntervalSink _focusIntervalSink;
    private readonly InputEventSink _inputEventSink;
    private readonly ILogger<PipeServer> _logger;
    private readonly JsonSerializerOptions _jsonOptions;

    public PipeServer(
        IStatusProvider statusProvider,
        ICommandExecutor commandExecutor,
        FocusIntervalSink focusIntervalSink,
        InputEventSink inputEventSink,
        ILogger<PipeServer> logger)
    {
        _statusProvider = statusProvider;
        _commandExecutor = commandExecutor;
        _focusIntervalSink = focusIntervalSink;
        _inputEventSink = inputEventSink;
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

                var server = NamedPipeServerStreamAcl.Create(
                    PipeProtocol.PipeName,
                    PipeDirection.InOut,
                    5, // maxNumberOfServerInstances (multiple RDP users)
                    PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous,
                    0, 0, pipeSecurity);

                _logger.LogInformation("PipeServer waiting for connection...");
                await server.WaitForConnectionAsync(ct);
                _logger.LogInformation("Pipe client connected");

                // Handle in background — HandleClientAsync owns disposal of server
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
        _logger.LogDebug("HandleClient: start, IsConnected={Connected}", server.IsConnected);
        try
        {
            var noBomUtf8 = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false);
            var reader = new StreamReader(server, noBomUtf8, leaveOpen: true);
            var writer = new StreamWriter(server, noBomUtf8, leaveOpen: true) { AutoFlush = true };

            while (server.IsConnected && !ct.IsCancellationRequested)
            {
                var line = await reader.ReadLineAsync(ct);
                if (line == null) break;

                PipeResponse response;
                try
                {
                    var request = JsonSerializer.Deserialize<PipeRequest>(line, _jsonOptions);
                    _logger.LogDebug("Pipe ← {Command}", request?.Command);
                    response = await ProcessRequestAsync(request!, ct);
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, "Pipe request processing error");
                    response = new PipeResponse { Success = false, Error = "Operation failed. Check service logs." };
                }

                var json = JsonSerializer.Serialize(response, _jsonOptions);
                _logger.LogDebug("Pipe → {Len} bytes", json.Length);
                await writer.WriteLineAsync(json);
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "HandleClient error");
        }
        finally
        {
            try { if (server.IsConnected) server.Disconnect(); } catch { }
            server.Dispose();
            _logger.LogDebug("HandleClient: disposed");
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

            case "report_focus_intervals":
                return HandleFocusIntervals(request);

            case "report_input_events":
                return HandleInputEvents(request);

            case "restart_service":
                // Removed: restart via pipe is a DoS risk. Use sc.exe with UAC elevation instead.
                return new PipeResponse { Success = false, Error = "Restart service via sc.exe (requires elevation)" };

            default:
                return new PipeResponse { Success = false, Error = $"Unknown command: {request.Command}" };
        }
    }

    private PipeResponse HandleInputEvents(PipeRequest request)
    {
        var events = request.InputEvents;
        if (events == null || events.Count == 0)
            return new PipeResponse { Success = true };

        var fallbackSessionId = _commandExecutor.CurrentSessionId;

        foreach (var data in events)
        {
            _inputEventSink.Enqueue(new InputEvent
            {
                Id = string.IsNullOrEmpty(data.Id) ? Guid.NewGuid().ToString() : data.Id,
                EventType = data.EventType,
                EventTs = DateTime.TryParse(data.EventTs, null,
                    System.Globalization.DateTimeStyles.RoundtripKind, out var ts) ? ts : DateTime.UtcNow,
                EventEndTs = data.EventEndTs != null && DateTime.TryParse(data.EventEndTs, null,
                    System.Globalization.DateTimeStyles.RoundtripKind, out var ets) ? ets : null,
                ClickX = data.ClickX,
                ClickY = data.ClickY,
                ClickButton = data.ClickButton,
                ClickType = data.ClickType,
                UiElementType = data.UiElementType,
                UiElementName = data.UiElementName,
                KeystrokeCount = data.KeystrokeCount,
                HasTypingBurst = data.HasTypingBurst,
                ScrollDirection = data.ScrollDirection,
                ScrollTotalDelta = data.ScrollTotalDelta,
                ScrollEventCount = data.ScrollEventCount,
                ProcessName = data.ProcessName,
                WindowTitle = data.WindowTitle,
                SegmentId = data.SegmentId,
                SegmentOffsetMs = data.SegmentOffsetMs,
                SessionId = data.SessionId ?? fallbackSessionId
            });
        }

        _logger.LogDebug("Enqueued {Count} input events from tray (sessionId={SessionId})",
            events.Count, fallbackSessionId ?? "none");
        return new PipeResponse { Success = true };
    }

    private PipeResponse HandleFocusIntervals(PipeRequest request)
    {
        var intervals = request.FocusIntervals;
        if (intervals == null || intervals.Count == 0)
            return new PipeResponse { Success = true };

        // T-159: Inject current recording session ID from service if tray didn't provide one.
        // TrayWindowTracker runs in user session and doesn't know the recording session ID.
        // PipeServer runs in service (Session 0) where CommandHandler tracks CurrentSessionId.
        var fallbackSessionId = _commandExecutor.CurrentSessionId;

        foreach (var data in intervals)
        {
            _focusIntervalSink.Enqueue(new FocusInterval
            {
                Id = string.IsNullOrEmpty(data.Id) ? Guid.NewGuid().ToString() : data.Id,
                ProcessName = data.ProcessName,
                WindowTitle = data.WindowTitle,
                IsBrowser = data.IsBrowser,
                BrowserName = data.BrowserName,
                Domain = data.Domain,
                StartedAt = DateTime.TryParse(data.StartedAt, null,
                    System.Globalization.DateTimeStyles.RoundtripKind, out var s) ? s : DateTime.UtcNow,
                EndedAt = data.EndedAt != null && DateTime.TryParse(data.EndedAt, null,
                    System.Globalization.DateTimeStyles.RoundtripKind, out var e) ? e : null,
                DurationMs = data.DurationMs,
                SessionId = data.SessionId ?? fallbackSessionId,
                WindowX = data.WindowX,
                WindowY = data.WindowY,
                WindowWidth = data.WindowWidth,
                WindowHeight = data.WindowHeight,
                IsMaximized = data.IsMaximized,
                IsFullscreen = data.IsFullscreen,
                MonitorIndex = data.MonitorIndex,
                IsIdle = data.IsIdle
            });
        }

        _logger.LogDebug("Enqueued {Count} focus intervals from tray (sessionId={SessionId})",
            intervals.Count, fallbackSessionId ?? "none");
        return new PipeResponse { Success = true };
    }
}
