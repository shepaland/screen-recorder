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
