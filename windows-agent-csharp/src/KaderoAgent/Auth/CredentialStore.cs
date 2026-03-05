using System.Text.Json;
using KaderoAgent.Configuration;
using KaderoAgent.Util;
using Microsoft.Extensions.Options;

namespace KaderoAgent.Auth;

public class CredentialStore
{
    private readonly string _filePath;

    public CredentialStore(IOptions<AgentConfig> config)
    {
        var dir = config.Value.DataPath;
        Directory.CreateDirectory(dir);
        _filePath = Path.Combine(dir, "credentials.enc");
    }

    public bool HasCredentials() => File.Exists(_filePath);

    public StoredCredentials? Load()
    {
        if (!File.Exists(_filePath)) return null;
        try
        {
            var encrypted = File.ReadAllBytes(_filePath);
            var json = CryptoUtil.Decrypt(encrypted, HardwareId.Generate());
            return JsonSerializer.Deserialize<StoredCredentials>(json);
        }
        catch
        {
            return null;
        }
    }

    public void Save(StoredCredentials creds)
    {
        var json = JsonSerializer.Serialize(creds);
        var encrypted = CryptoUtil.Encrypt(json, HardwareId.Generate());
        File.WriteAllBytes(_filePath, encrypted);
    }

    public void Clear()
    {
        if (File.Exists(_filePath)) File.Delete(_filePath);
        ClearPendingRegistration();
    }

    /// <summary>
    /// Saves server URL and registration token for later completion via SetupForm/Tray.
    /// Used by installer when username/password are not yet known.
    /// </summary>
    public void SavePendingRegistration(string serverUrl, string registrationToken)
    {
        var dir = Path.GetDirectoryName(_filePath)!;
        var pendingPath = Path.Combine(dir, "pending_registration.json");
        var json = JsonSerializer.Serialize(new PendingRegistration
        {
            ServerUrl = serverUrl,
            RegistrationToken = registrationToken
        });
        File.WriteAllText(pendingPath, json);
    }

    /// <summary>
    /// Loads pending registration config saved by installer.
    /// </summary>
    public PendingRegistration? LoadPendingRegistration()
    {
        var dir = Path.GetDirectoryName(_filePath)!;
        var pendingPath = Path.Combine(dir, "pending_registration.json");
        if (!File.Exists(pendingPath)) return null;
        try
        {
            var json = File.ReadAllText(pendingPath);
            return JsonSerializer.Deserialize<PendingRegistration>(json);
        }
        catch { return null; }
    }

    /// <summary>
    /// Clears pending registration file after successful registration.
    /// </summary>
    public void ClearPendingRegistration()
    {
        var dir = Path.GetDirectoryName(_filePath)!;
        var pendingPath = Path.Combine(dir, "pending_registration.json");
        if (File.Exists(pendingPath)) File.Delete(pendingPath);
    }
}

public class StoredCredentials
{
    public string ServerUrl { get; set; } = "";
    public string DeviceId { get; set; } = "";
    public string RefreshToken { get; set; } = "";
    public string AccessToken { get; set; } = "";
    public ServerConfig? ServerConfig { get; set; }
}

public class PendingRegistration
{
    public string ServerUrl { get; set; } = "";
    public string RegistrationToken { get; set; } = "";
}
