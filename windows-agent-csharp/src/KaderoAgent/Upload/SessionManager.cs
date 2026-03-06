using System.Net;
using KaderoAgent.Auth;
using KaderoAgent.Util;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Upload;

public class SessionManager
{
    private readonly ApiClient _apiClient;
    private readonly AuthManager _authManager;
    private readonly CredentialStore _credentialStore;
    private readonly ILogger<SessionManager> _logger;
    private string? _currentSessionId;

    public string? CurrentSessionId => _currentSessionId;

    public SessionManager(ApiClient apiClient, AuthManager authManager,
        CredentialStore credentialStore, ILogger<SessionManager> logger)
    {
        _apiClient = apiClient;
        _authManager = authManager;
        _credentialStore = credentialStore;
        _logger = logger;
    }

    public async Task<string> StartSessionAsync(int fps = 1, string resolution = "1280x720",
        CancellationToken ct = default)
    {
        await _authManager.EnsureValidTokenAsync();
        var creds = _credentialStore.Load();
        var baseUrl = creds?.ServerUrl?.TrimEnd('/') ?? "";

        var url = $"{baseUrl}/api/ingest/v1/ingest/sessions";
        var body = new
        {
            device_id = _authManager.DeviceId,
            metadata = new
            {
                resolution,
                fps,
                codec = "h264"
            }
        };

        try
        {
            var response = await _apiClient.PostAsync<SessionResponse>(url, body, ct);
            _currentSessionId = response?.Id;
            _logger.LogInformation("Recording session started: {SessionId}", _currentSessionId);
            return _currentSessionId ?? throw new Exception("Failed to create session");
        }
        catch (HttpRequestException ex) when (ex.StatusCode == HttpStatusCode.Conflict)
        {
            _logger.LogWarning("Server returned 409 Conflict (active session exists), closing stale session...");

            // Try to find and close the active session, then retry
            await CloseActiveSessionForDeviceAsync(baseUrl, ct);

            // Retry session creation
            var response = await _apiClient.PostAsync<SessionResponse>(url, body, ct);
            _currentSessionId = response?.Id;
            _logger.LogInformation("Recording session started after closing stale: {SessionId}", _currentSessionId);
            return _currentSessionId ?? throw new Exception("Failed to create session after closing stale");
        }
    }

    public async Task EndSessionAsync(CancellationToken ct = default)
    {
        if (_currentSessionId == null) return;

        try
        {
            await _authManager.EnsureValidTokenAsync();
            var creds = _credentialStore.Load();
            var baseUrl = creds?.ServerUrl?.TrimEnd('/') ?? "";

            var url = $"{baseUrl}/api/ingest/v1/ingest/sessions/{_currentSessionId}/end";
            await _apiClient.PutAsync<object>(url, null, ct);
            _logger.LogInformation("Recording session ended: {SessionId}", _currentSessionId);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to end session {SessionId} on server", _currentSessionId);
        }
        finally
        {
            _currentSessionId = null;
        }
    }

    /// <summary>
    /// Close any active recording session for this device on the server.
    /// Used to recover from 409 Conflict when stale sessions exist.
    /// </summary>
    private async Task CloseActiveSessionForDeviceAsync(string baseUrl, CancellationToken ct)
    {
        try
        {
            // Get recordings for this device to find active sessions
            var deviceId = _authManager.DeviceId;
            var listUrl = $"{baseUrl}/api/ingest/v1/ingest/recordings?device_id={deviceId}&status=active&size=10";

            var listResponse = await _apiClient.GetAsync<RecordingsListResponse>(listUrl, ct);
            if (listResponse?.Content == null || listResponse.Content.Count == 0)
            {
                _logger.LogWarning("No active sessions found for device, but server returned 409");
                return;
            }

            foreach (var session in listResponse.Content)
            {
                try
                {
                    var endUrl = $"{baseUrl}/api/ingest/v1/ingest/sessions/{session.Id}/end";
                    await _apiClient.PutAsync<object>(endUrl, null, ct);
                    _logger.LogInformation("Closed stale session: {SessionId}", session.Id);
                }
                catch (Exception ex)
                {
                    _logger.LogWarning(ex, "Failed to close stale session {SessionId}", session.Id);
                }
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to list/close active sessions for device");
        }
    }
}

public class SessionResponse
{
    public string? Id { get; set; }
    public string? Status { get; set; }
    public string? StartedTs { get; set; }
}

public class RecordingsListResponse
{
    public List<RecordingItem>? Content { get; set; }
}

public class RecordingItem
{
    public string? Id { get; set; }
    public string? Status { get; set; }
}
