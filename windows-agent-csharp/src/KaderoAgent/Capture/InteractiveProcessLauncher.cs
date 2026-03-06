using System.Runtime.InteropServices;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Capture;

/// <summary>
/// Launches a process in the active user's desktop session from Session 0 (Windows service).
/// Enumerates all WTS sessions to find one with a logged-in user (supports RDP, console, etc.).
/// </summary>
public static class InteractiveProcessLauncher
{
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint WTSGetActiveConsoleSessionId();

    [DllImport("wtsapi32.dll", SetLastError = true)]
    private static extern bool WTSQueryUserToken(uint sessionId, out IntPtr token);

    [DllImport("wtsapi32.dll", SetLastError = true)]
    private static extern bool WTSEnumerateSessions(
        IntPtr hServer, int reserved, int version,
        out IntPtr ppSessionInfo, out int pCount);

    [DllImport("wtsapi32.dll")]
    private static extern void WTSFreeMemory(IntPtr pMemory);

    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CreateProcessAsUser(
        IntPtr hToken,
        string? lpApplicationName,
        string lpCommandLine,
        IntPtr lpProcessAttributes,
        IntPtr lpThreadAttributes,
        bool bInheritHandles,
        uint dwCreationFlags,
        IntPtr lpEnvironment,
        string? lpCurrentDirectory,
        ref STARTUPINFO lpStartupInfo,
        out PROCESS_INFORMATION lpProcessInformation);

    [DllImport("userenv.dll", SetLastError = true)]
    private static extern bool CreateEnvironmentBlock(out IntPtr lpEnvironment, IntPtr hToken, bool bInherit);

    [DllImport("userenv.dll", SetLastError = true)]
    private static extern bool DestroyEnvironmentBlock(IntPtr lpEnvironment);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool DuplicateTokenEx(
        IntPtr hExistingToken,
        uint dwDesiredAccess,
        IntPtr lpTokenAttributes,
        int impersonationLevel,
        int tokenType,
        out IntPtr phNewToken);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr hObject);

    [StructLayout(LayoutKind.Sequential)]
    private struct WTS_SESSION_INFO
    {
        public uint SessionId;
        public IntPtr pWinStationName;
        public WTS_CONNECTSTATE_CLASS State;
    }

    private enum WTS_CONNECTSTATE_CLASS
    {
        WTSActive = 0,
        WTSConnected = 1,
        WTSConnectQuery = 2,
        WTSShadow = 3,
        WTSDisconnected = 4,
        WTSIdle = 5,
        WTSListen = 6,
        WTSReset = 7,
        WTSDown = 8,
        WTSInit = 9
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct STARTUPINFO
    {
        public int cb;
        public string lpReserved;
        public string lpDesktop;
        public string lpTitle;
        public int dwX;
        public int dwY;
        public int dwXSize;
        public int dwYSize;
        public int dwXCountChars;
        public int dwYCountChars;
        public int dwFillAttribute;
        public int dwFlags;
        public short wShowWindow;
        public short cbReserved2;
        public IntPtr lpReserved2;
        public IntPtr hStdInput;
        public IntPtr hStdOutput;
        public IntPtr hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PROCESS_INFORMATION
    {
        public IntPtr hProcess;
        public IntPtr hThread;
        public int dwProcessId;
        public int dwThreadId;
    }

    private const uint TOKEN_ALL_ACCESS = 0x000F01FF;
    private const int SecurityImpersonation = 2;
    private const int TokenPrimary = 1;
    private const uint CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    private const uint CREATE_NO_WINDOW = 0x08000000;
    private static readonly IntPtr WTS_CURRENT_SERVER_HANDLE = IntPtr.Zero;

    /// <summary>
    /// Launches a process in an active user session. Returns the process ID, or -1 on failure.
    /// Tries console session first, then enumerates all WTS sessions (RDP, etc.).
    /// </summary>
    public static int LaunchInUserSession(string exePath, string arguments, string? workingDirectory, ILogger logger)
    {
        // Strategy 1: Try active console session first
        var consoleSessionId = WTSGetActiveConsoleSessionId();
        if (consoleSessionId != 0xFFFFFFFF)
        {
            logger.LogInformation("Trying console session: {SessionId}", consoleSessionId);
            var pid = TryLaunchInSession(consoleSessionId, exePath, arguments, workingDirectory, logger);
            if (pid > 0) return pid;
            logger.LogWarning("Console session {SessionId} failed, trying other sessions...", consoleSessionId);
        }

        // Strategy 2: Enumerate all WTS sessions and find active ones with a user
        var sessionIds = GetActiveUserSessions(logger);
        foreach (var sid in sessionIds)
        {
            if (sid == consoleSessionId) continue; // Already tried
            logger.LogInformation("Trying WTS session: {SessionId}", sid);
            var pid = TryLaunchInSession(sid, exePath, arguments, workingDirectory, logger);
            if (pid > 0) return pid;
        }

        logger.LogError("No user session available to launch FFmpeg. Sessions tried: console={Console}, WTS=[{Sessions}]",
            consoleSessionId, string.Join(",", sessionIds));
        return -1;
    }

    private static List<uint> GetActiveUserSessions(ILogger logger)
    {
        var result = new List<uint>();

        if (!WTSEnumerateSessions(WTS_CURRENT_SERVER_HANDLE, 0, 1, out var pSessionInfo, out var count))
        {
            logger.LogWarning("WTSEnumerateSessions failed: error {Error}", Marshal.GetLastWin32Error());
            return result;
        }

        try
        {
            var structSize = Marshal.SizeOf<WTS_SESSION_INFO>();
            for (var i = 0; i < count; i++)
            {
                var sessionInfo = Marshal.PtrToStructure<WTS_SESSION_INFO>(
                    pSessionInfo + i * structSize);

                // Only consider Active or Disconnected sessions (both have user tokens)
                // Skip session 0 (services session) and Listener sessions
                if (sessionInfo.SessionId == 0) continue;
                if (sessionInfo.State == WTS_CONNECTSTATE_CLASS.WTSActive ||
                    sessionInfo.State == WTS_CONNECTSTATE_CLASS.WTSDisconnected)
                {
                    var stationName = Marshal.PtrToStringUni(sessionInfo.pWinStationName) ?? "?";
                    logger.LogDebug("Found session {Id}: state={State}, station={Station}",
                        sessionInfo.SessionId, sessionInfo.State, stationName);
                    result.Add(sessionInfo.SessionId);
                }
            }
        }
        finally
        {
            WTSFreeMemory(pSessionInfo);
        }

        return result;
    }

    private static int TryLaunchInSession(uint sessionId, string exePath, string arguments,
        string? workingDirectory, ILogger logger)
    {
        IntPtr userToken = IntPtr.Zero;
        IntPtr duplicateToken = IntPtr.Zero;
        IntPtr envBlock = IntPtr.Zero;

        try
        {
            if (!WTSQueryUserToken(sessionId, out userToken))
            {
                var err = Marshal.GetLastWin32Error();
                logger.LogDebug("WTSQueryUserToken failed for session {SessionId}: error {Error}", sessionId, err);
                return -1;
            }

            if (!DuplicateTokenEx(userToken, TOKEN_ALL_ACCESS, IntPtr.Zero,
                    SecurityImpersonation, TokenPrimary, out duplicateToken))
            {
                logger.LogDebug("DuplicateTokenEx failed for session {SessionId}: error {Error}",
                    sessionId, Marshal.GetLastWin32Error());
                return -1;
            }

            if (!CreateEnvironmentBlock(out envBlock, duplicateToken, false))
            {
                logger.LogWarning("CreateEnvironmentBlock failed, using null env");
                envBlock = IntPtr.Zero;
            }

            var si = new STARTUPINFO();
            si.cb = Marshal.SizeOf(si);
            si.lpDesktop = @"winsta0\default";

            var commandLine = $"\"{exePath}\" {arguments}";
            uint creationFlags = CREATE_UNICODE_ENVIRONMENT | CREATE_NO_WINDOW;

            if (!CreateProcessAsUser(duplicateToken, null, commandLine,
                    IntPtr.Zero, IntPtr.Zero, false, creationFlags, envBlock,
                    workingDirectory, ref si, out var pi))
            {
                var err = Marshal.GetLastWin32Error();
                logger.LogDebug("CreateProcessAsUser failed for session {SessionId}: error {Error}", sessionId, err);
                return -1;
            }

            CloseHandle(pi.hProcess);
            CloseHandle(pi.hThread);

            logger.LogInformation("Process launched in session {SessionId}: PID={Pid}", sessionId, pi.dwProcessId);
            return pi.dwProcessId;
        }
        finally
        {
            if (envBlock != IntPtr.Zero) DestroyEnvironmentBlock(envBlock);
            if (duplicateToken != IntPtr.Zero) CloseHandle(duplicateToken);
            if (userToken != IntPtr.Zero) CloseHandle(userToken);
        }
    }
}
