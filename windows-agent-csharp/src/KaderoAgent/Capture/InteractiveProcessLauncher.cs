using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Capture;

/// <summary>
/// Launches a process in the active user's desktop session from Session 0 (Windows service).
/// Uses WTSQueryUserToken + CreateProcessAsUser Win32 APIs.
/// </summary>
public static class InteractiveProcessLauncher
{
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint WTSGetActiveConsoleSessionId();

    [DllImport("wtsapi32.dll", SetLastError = true)]
    private static extern bool WTSQueryUserToken(uint sessionId, out IntPtr token);

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

    [DllImport("kernel32.dll")]
    private static extern uint GetLastError();

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

    /// <summary>
    /// Launches a process in the active console session. Returns the process ID, or -1 on failure.
    /// </summary>
    public static int LaunchInUserSession(string exePath, string arguments, string? workingDirectory, ILogger logger)
    {
        var sessionId = WTSGetActiveConsoleSessionId();
        if (sessionId == 0xFFFFFFFF)
        {
            logger.LogWarning("No active console session found");
            return -1;
        }

        logger.LogInformation("Active console session: {SessionId}", sessionId);

        IntPtr userToken = IntPtr.Zero;
        IntPtr duplicateToken = IntPtr.Zero;
        IntPtr envBlock = IntPtr.Zero;

        try
        {
            if (!WTSQueryUserToken(sessionId, out userToken))
            {
                var err = Marshal.GetLastWin32Error();
                logger.LogError("WTSQueryUserToken failed: error {Error}", err);
                return -1;
            }

            if (!DuplicateTokenEx(userToken, TOKEN_ALL_ACCESS, IntPtr.Zero,
                    SecurityImpersonation, TokenPrimary, out duplicateToken))
            {
                logger.LogError("DuplicateTokenEx failed: error {Error}", Marshal.GetLastWin32Error());
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
                logger.LogError("CreateProcessAsUser failed: error {Error}", err);
                return -1;
            }

            CloseHandle(pi.hProcess);
            CloseHandle(pi.hThread);

            logger.LogInformation("Process launched in user session: PID={Pid}", pi.dwProcessId);
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
