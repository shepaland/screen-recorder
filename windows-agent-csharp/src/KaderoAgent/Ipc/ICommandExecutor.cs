namespace KaderoAgent.Ipc;

public interface ICommandExecutor
{
    Task<bool> ReconnectAsync(string? newServerUrl, string? newToken, CancellationToken ct);

    /// <summary>
    /// Current active recording session ID (null if not recording).
    /// Used by PipeServer to inject session_id into focus intervals from tray process.
    /// </summary>
    string? CurrentSessionId { get; }
}
