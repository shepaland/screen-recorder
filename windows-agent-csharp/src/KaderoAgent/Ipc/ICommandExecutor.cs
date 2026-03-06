namespace KaderoAgent.Ipc;

public interface ICommandExecutor
{
    Task<bool> ReconnectAsync(string? newServerUrl, string? newToken, CancellationToken ct);
}
