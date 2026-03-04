using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using KaderoAgent.Auth;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Util;

public class ApiClient
{
    private readonly HttpClient _http;
    private readonly TokenStore _tokenStore;
    private readonly ILogger<ApiClient> _logger;
    private readonly SemaphoreSlim _refreshLock = new(1, 1);
    private string? _deviceId;

    public static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        PropertyNameCaseInsensitive = true
    };

    public ApiClient(TokenStore tokenStore, ILogger<ApiClient> logger)
    {
        _tokenStore = tokenStore;
        _logger = logger;
        _http = new HttpClient();
        _http.Timeout = TimeSpan.FromSeconds(30);
    }

    public void SetDeviceId(string deviceId) => _deviceId = deviceId;

    public async Task<T?> GetAsync<T>(string url, CancellationToken ct = default)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, url);
        AddAuth(request);
        var response = await SendWithRetry(request, ct);
        response.EnsureSuccessStatusCode();
        var json = await response.Content.ReadAsStringAsync(ct);
        return JsonSerializer.Deserialize<T>(json, JsonOptions);
    }

    public async Task<T?> PostAsync<T>(string url, object? body = null, CancellationToken ct = default)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, url);
        if (body != null)
        {
            request.Content = new StringContent(
                JsonSerializer.Serialize(body, JsonOptions), Encoding.UTF8, "application/json");
        }
        AddAuth(request);
        var response = await SendWithRetry(request, ct);
        response.EnsureSuccessStatusCode();
        var json = await response.Content.ReadAsStringAsync(ct);
        if (string.IsNullOrWhiteSpace(json)) return default;
        return JsonSerializer.Deserialize<T>(json, JsonOptions);
    }

    public async Task<T?> PutAsync<T>(string url, object? body = null, CancellationToken ct = default)
    {
        var request = new HttpRequestMessage(HttpMethod.Put, url);
        if (body != null)
        {
            request.Content = new StringContent(
                JsonSerializer.Serialize(body, JsonOptions), Encoding.UTF8, "application/json");
        }
        AddAuth(request);
        var response = await SendWithRetry(request, ct);
        response.EnsureSuccessStatusCode();
        var json = await response.Content.ReadAsStringAsync(ct);
        if (string.IsNullOrWhiteSpace(json)) return default;
        return JsonSerializer.Deserialize<T>(json, JsonOptions);
    }

    public async Task<HttpResponseMessage> PutBinaryAsync(string url, byte[] data, string contentType, CancellationToken ct = default)
    {
        var request = new HttpRequestMessage(HttpMethod.Put, url);
        request.Content = new ByteArrayContent(data);
        request.Content.Headers.ContentType = new MediaTypeHeaderValue(contentType);
        // No auth for presigned URLs
        return await _http.SendAsync(request, ct);
    }

    private void AddAuth(HttpRequestMessage request)
    {
        var token = _tokenStore.AccessToken;
        if (!string.IsNullOrEmpty(token))
        {
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        }
        if (!string.IsNullOrEmpty(_deviceId))
        {
            request.Headers.TryAddWithoutValidation("X-Device-ID", _deviceId);
        }
    }

    private async Task<HttpResponseMessage> SendWithRetry(HttpRequestMessage request, CancellationToken ct, int maxRetries = 3)
    {
        for (int i = 0; i < maxRetries; i++)
        {
            try
            {
                // Clone request for retry
                var clone = await CloneRequest(request);
                var response = await _http.SendAsync(clone, ct);

                if (response.StatusCode != HttpStatusCode.ServiceUnavailable &&
                    response.StatusCode != HttpStatusCode.GatewayTimeout)
                    return response;

                _logger.LogWarning("Request to {Url} returned {Status}, retry {N}/{Max}",
                    request.RequestUri, response.StatusCode, i + 1, maxRetries);
            }
            catch (HttpRequestException ex) when (i < maxRetries - 1)
            {
                _logger.LogWarning(ex, "Request failed, retry {N}/{Max}", i + 1, maxRetries);
            }

            await Task.Delay(TimeSpan.FromSeconds(Math.Pow(2, i)), ct);
        }

        throw new HttpRequestException($"Request failed after {maxRetries} retries");
    }

    private static async Task<HttpRequestMessage> CloneRequest(HttpRequestMessage req)
    {
        var clone = new HttpRequestMessage(req.Method, req.RequestUri);
        foreach (var header in req.Headers)
            clone.Headers.TryAddWithoutValidation(header.Key, header.Value);
        if (req.Content != null)
        {
            var content = await req.Content.ReadAsByteArrayAsync();
            clone.Content = new ByteArrayContent(content);
            foreach (var header in req.Content.Headers)
                clone.Content.Headers.TryAddWithoutValidation(header.Key, header.Value);
        }
        return clone;
    }
}
