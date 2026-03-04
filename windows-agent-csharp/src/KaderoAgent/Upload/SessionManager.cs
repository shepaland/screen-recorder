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

    public async Task<string> StartSessionAsync(CancellationToken ct = default)
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
                resolution = "1920x1080",
                fps = 5,
                codec = "h264"
            }
        };

        var response = await _apiClient.PostAsync<SessionResponse>(url, body, ct);
        _currentSessionId = response?.Id;
        _logger.LogInformation("Recording session started: {SessionId}", _currentSessionId);
        return _currentSessionId ?? throw new Exception("Failed to create session");
    }

    public async Task EndSessionAsync(CancellationToken ct = default)
    {
        if (_currentSessionId == null) return;

        await _authManager.EnsureValidTokenAsync();
        var creds = _credentialStore.Load();
        var baseUrl = creds?.ServerUrl?.TrimEnd('/') ?? "";

        var url = $"{baseUrl}/api/ingest/v1/ingest/sessions/{_currentSessionId}/end";
        await _apiClient.PutAsync<object>(url, null, ct);
        _logger.LogInformation("Recording session ended: {SessionId}", _currentSessionId);
        _currentSessionId = null;
    }
}

public class SessionResponse
{
    public string? Id { get; set; }
    public string? Status { get; set; }
    public string? StartedTs { get; set; }
}
