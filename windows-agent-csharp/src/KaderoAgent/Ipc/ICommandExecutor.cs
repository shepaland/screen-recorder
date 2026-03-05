namespace KaderoAgent.Ipc;

public interface ICommandExecutor
{
    Task<bool> ReconnectAsync(string? newServerUrl, string? newToken,
        string? username, string? password, CancellationToken ct);
}
