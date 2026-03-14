using System.Runtime.InteropServices;
using Microsoft.Extensions.Logging;
using Microsoft.Win32.SafeHandles;

namespace KaderoAgent.Capture;

/// <summary>
/// Launches a process in the active user's desktop session from Session 0 (Windows service).
/// Enumerates all WTS sessions to find one with a logged-in user (supports RDP, console, etc.).
/// Sets token session ID and grants desktop permissions for GDI screen capture.
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

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool SetTokenInformation(
        IntPtr tokenHandle,
        int tokenInformationClass,
        ref uint tokenInformation,
        uint tokenInformationLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr hObject);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern SafeFileHandle CreateFile(
        string lpFileName,
        uint dwDesiredAccess,
        uint dwShareMode,
        IntPtr lpSecurityAttributes,
        uint dwCreationDisposition,
        uint dwFlagsAndAttributes,
        IntPtr hTemplateFile);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr OpenWindowStation(
        [MarshalAs(UnmanagedType.LPStr)] string lpszWinSta,
        bool fInherit,
        uint dwDesiredAccess);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr OpenDesktop(
        [MarshalAs(UnmanagedType.LPStr)] string lpszDesktop,
        uint dwFlags,
        bool fInherit,
        uint dwDesiredAccess);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetProcessWindowStation(IntPtr hWinSta);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr GetProcessWindowStation();

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool CloseDesktop(IntPtr hDesktop);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool CloseWindowStation(IntPtr hWinSta);

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

    [StructLayout(LayoutKind.Sequential)]
    private struct SECURITY_ATTRIBUTES
    {
        public int nLength;
        public IntPtr lpSecurityDescriptor;
        public bool bInheritHandle;
    }

    private const uint TOKEN_ALL_ACCESS = 0x000F01FF;
    private const int SecurityImpersonation = 2;
    private const int TokenPrimary = 1;
    private const int TokenSessionId = 12;
    private const uint CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    private const uint CREATE_NO_WINDOW = 0x08000000;
    private const uint GENERIC_READ = 0x80000000;
    private const uint GENERIC_WRITE = 0x40000000;
    private const uint FILE_SHARE_WRITE = 0x00000002;
    private const uint CREATE_ALWAYS = 2;
    private const uint FILE_ATTRIBUTE_NORMAL = 0x80;
    private const int STARTF_USESTDHANDLES = 0x00000100;
    private const uint WINSTA_ALL_ACCESS = 0x37F;
    private const uint DESKTOP_ALL_ACCESS = 0x01FF;
    private static readonly IntPtr WTS_CURRENT_SERVER_HANDLE = IntPtr.Zero;

    /// <summary>
    /// Launches a process in an active user session. Returns the process ID, or -1 on failure.
    /// Tries console session first, then enumerates all WTS sessions (RDP, etc.).
    /// </summary>
    public static int LaunchInUserSession(string exePath, string arguments,
        string? workingDirectory, ILogger logger, string? stderrLogPath = null)
    {
        // Strategy 1: Try active console session first
        var consoleSessionId = WTSGetActiveConsoleSessionId();
        if (consoleSessionId != 0xFFFFFFFF)
        {
            logger.LogInformation("Trying console session: {SessionId}", consoleSessionId);
            var pid = TryLaunchInSession(consoleSessionId, exePath, arguments,
                workingDirectory, logger, stderrLogPath);
            if (pid > 0 && IsProcessAlive(pid, logger)) return pid;
            if (pid > 0) logger.LogWarning("Process {PID} in console session {SessionId} exited immediately", pid, consoleSessionId);
            else logger.LogWarning("Console session {SessionId} failed, trying other sessions...", consoleSessionId);
        }

        // Strategy 2: Enumerate all WTS sessions (Active first, then Disconnected)
        var sessionIds = GetActiveUserSessions(logger);
        foreach (var sid in sessionIds)
        {
            if (sid == consoleSessionId) continue; // Already tried
            logger.LogInformation("Trying WTS session: {SessionId}", sid);
            var pid = TryLaunchInSession(sid, exePath, arguments,
                workingDirectory, logger, stderrLogPath);
            if (pid > 0 && IsProcessAlive(pid, logger)) return pid;
            if (pid > 0) logger.LogWarning("Process {PID} in session {SessionId} exited immediately, trying next...", pid, sid);
        }

        logger.LogError("No user session available to launch process. Sessions tried: console={Console}, WTS=[{Sessions}]",
            consoleSessionId, string.Join(",", sessionIds));
        return -1;
    }

    /// <summary>Wait briefly and check if process is still alive (FFmpeg may crash immediately on inaccessible desktop).</summary>
    private static bool IsProcessAlive(int pid, ILogger logger)
    {
        try
        {
            Thread.Sleep(500); // Give process time to crash if desktop inaccessible
            using var proc = System.Diagnostics.Process.GetProcessById(pid);
            return !proc.HasExited;
        }
        catch
        {
            logger.LogDebug("Process {PID} not found after launch — likely exited", pid);
            return false;
        }
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

                if (sessionInfo.SessionId == 0) continue;
                if (sessionInfo.State == WTS_CONNECTSTATE_CLASS.WTSActive ||
                    sessionInfo.State == WTS_CONNECTSTATE_CLASS.WTSDisconnected)
                {
                    var stationName = Marshal.PtrToStringUni(sessionInfo.pWinStationName) ?? "?";
                    logger.LogDebug("Found session {Id}: state={State}, station={Station}",
                        sessionInfo.SessionId, sessionInfo.State, stationName);
                    // Active sessions first, disconnected last
                    if (sessionInfo.State == WTS_CONNECTSTATE_CLASS.WTSActive)
                        result.Insert(0, sessionInfo.SessionId);
                    else
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
        string? workingDirectory, ILogger logger, string? stderrLogPath)
    {
        IntPtr userToken = IntPtr.Zero;
        IntPtr duplicateToken = IntPtr.Zero;
        IntPtr envBlock = IntPtr.Zero;
        SafeFileHandle? stderrHandle = null;

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

            // Set the token's session ID to the target session.
            // This is critical for GDI capture — without it, the process
            // may run in session 0 despite having the user's token.
            var targetSessionId = sessionId;
            if (!SetTokenInformation(duplicateToken, TokenSessionId,
                    ref targetSessionId, sizeof(uint)))
            {
                logger.LogWarning("SetTokenInformation(SessionId) failed for session {SessionId}: error {Error}",
                    sessionId, Marshal.GetLastWin32Error());
                // Continue anyway — CreateProcessAsUser may still work
            }

            if (!CreateEnvironmentBlock(out envBlock, duplicateToken, false))
            {
                logger.LogWarning("CreateEnvironmentBlock failed, using null env");
                envBlock = IntPtr.Zero;
            }

            var si = new STARTUPINFO();
            si.cb = Marshal.SizeOf(si);
            si.lpDesktop = @"winsta0\default";

            // Redirect stderr to a log file for diagnostics
            if (!string.IsNullOrEmpty(stderrLogPath))
            {
                try
                {
                    var sa = new SECURITY_ATTRIBUTES
                    {
                        nLength = Marshal.SizeOf<SECURITY_ATTRIBUTES>(),
                        bInheritHandle = true
                    };
                    var saPtr = Marshal.AllocHGlobal(sa.nLength);
                    Marshal.StructureToPtr(sa, saPtr, false);

                    stderrHandle = CreateFile(stderrLogPath,
                        GENERIC_WRITE, FILE_SHARE_WRITE, saPtr,
                        CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, IntPtr.Zero);

                    Marshal.FreeHGlobal(saPtr);

                    if (stderrHandle != null && !stderrHandle.IsInvalid)
                    {
                        si.hStdError = stderrHandle.DangerousGetHandle();
                        si.dwFlags |= STARTF_USESTDHANDLES;
                        logger.LogDebug("FFmpeg stderr redirected to {Path}", stderrLogPath);
                    }
                }
                catch (Exception ex)
                {
                    logger.LogDebug(ex, "Failed to redirect stderr to file");
                }
            }

            var commandLine = $"\"{exePath}\" {arguments}";
            uint creationFlags = CREATE_UNICODE_ENVIRONMENT | CREATE_NO_WINDOW;

            if (!CreateProcessAsUser(duplicateToken, null, commandLine,
                    IntPtr.Zero, IntPtr.Zero,
                    stderrHandle != null && !stderrHandle.IsInvalid, // inherit handles only if stderr redirect
                    creationFlags, envBlock,
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
            stderrHandle?.Dispose();
            if (envBlock != IntPtr.Zero) DestroyEnvironmentBlock(envBlock);
            if (duplicateToken != IntPtr.Zero) CloseHandle(duplicateToken);
            if (userToken != IntPtr.Zero) CloseHandle(userToken);
        }
    }
}
