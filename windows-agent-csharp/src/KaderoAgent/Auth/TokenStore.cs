namespace KaderoAgent.Auth;

public class TokenStore
{
    private string? _accessToken;
    private string? _refreshToken;
    private DateTime _accessTokenExpiry = DateTime.MinValue;
    private readonly object _lock = new();

    public string? AccessToken
    {
        get { lock (_lock) return _accessToken; }
        set { lock (_lock) _accessToken = value; }
    }

    public string? RefreshToken
    {
        get { lock (_lock) return _refreshToken; }
        set { lock (_lock) _refreshToken = value; }
    }

    public DateTime AccessTokenExpiry
    {
        get { lock (_lock) return _accessTokenExpiry; }
        set { lock (_lock) _accessTokenExpiry = value; }
    }

    public bool IsAccessTokenExpired()
    {
        var expiry = AccessTokenExpiry;
        // DateTime.MinValue.AddMinutes(-2) throws ArgumentOutOfRangeException
        if (expiry == DateTime.MinValue) return true;
        return DateTime.UtcNow >= expiry.AddMinutes(-2);
    }
}
