using System.Runtime.InteropServices;
using KaderoAgent.Audit;
using KaderoAgent.Auth;
using KaderoAgent.Capture;
using KaderoAgent.Command;
using KaderoAgent.Configuration;
using KaderoAgent.Ipc;
using KaderoAgent.Service;
using KaderoAgent.Storage;
using KaderoAgent.Upload;
using KaderoAgent.Util;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

// Configure log4net — set log path before loading config
var logPath = Path.Combine(
    Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
    "Kadero", "logs");
try { Directory.CreateDirectory(logPath); } catch { /* ACL may not be set yet during install */ }
log4net.GlobalContext.Properties["LogPath"] = logPath;

// Initialize log4net directly (for --tray and --test-ui which exit before Host builder)
var log4netCfg = Path.Combine(AppContext.BaseDirectory, "log4net.config");
if (File.Exists(log4netCfg))
{
    log4net.Config.XmlConfigurator.Configure(new FileInfo(log4netCfg));
}

// P/Invoke for attaching to parent console (WinExe has no console by default)
[DllImport("kernel32.dll")]
static extern bool AttachConsole(int dwProcessId);
const int ATTACH_PARENT_PROCESS = -1;

// Glass UI test mode: create all windows, verify properties, exit.
// Must run on STA thread because top-level await (line 236) makes Main async → MTA.
if (args.Contains("--test-ui"))
{
    AttachConsole(ATTACH_PARENT_PROCESS);

    var exitCode = 0;
    var staThread = new Thread(() =>
    {
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        var passed = 0;
        var failed = 0;
        void Assert(string name, bool condition) {
            if (condition) { Console.WriteLine($"  PASS: {name}"); passed++; }
            else { Console.WriteLine($"  FAIL: {name}"); failed++; }
        }

        Console.WriteLine("=== GlassHelper ===");
        Assert("AccentColor is #dc2626", KaderoAgent.Tray.GlassHelper.AccentColor.R == 220 && KaderoAgent.Tray.GlassHelper.AccentColor.G == 38);
        Assert("TextPrimary is White", KaderoAgent.Tray.GlassHelper.TextPrimary == System.Drawing.Color.White);
        Assert("BackgroundColor alpha=200", KaderoAgent.Tray.GlassHelper.BackgroundColor.A == 200);

        Console.WriteLine("=== AboutDialog ===");
        var about = new KaderoAgent.Tray.AboutDialog();
        Assert("Title is 'О программе'", about.Text == "О программе");
        Assert("Borderless", about.FormBorderStyle == System.Windows.Forms.FormBorderStyle.None);
        Assert("Size 350x220", about.Width == 350 && about.Height == 220);
        Assert("Has controls", about.Controls.Count >= 4);
        about.Dispose();

        Console.WriteLine("=== StatusWindow ===");
        var pipe = new KaderoAgent.Ipc.PipeClient();
        var status = new KaderoAgent.Tray.StatusWindow(pipe, () => {});
        Assert("Title contains 'Кадеро'", status.Text.Contains("Кадеро"));
        Assert("Borderless", status.FormBorderStyle == System.Windows.Forms.FormBorderStyle.None);
        Assert("Size 480x720", status.Width == 480 && status.Height == 720);
        Assert("Manual position", status.StartPosition == System.Windows.Forms.FormStartPosition.Manual);
        var wa = System.Windows.Forms.Screen.FromPoint(System.Windows.Forms.Cursor.Position).WorkingArea;
        var expectedX = wa.Right - status.Width - 12;
        var expectedY = wa.Bottom - status.Height - 12;
        Assert($"Bottom-right position ({status.Location.X},{status.Location.Y} vs {expectedX},{expectedY})",
            Math.Abs(status.Location.X - expectedX) < 5 && Math.Abs(status.Location.Y - expectedY) < 5);
        Assert("Has many controls", status.Controls.Count >= 20);
        status.Dispose();
        pipe.Dispose();

        Console.WriteLine($"\n=== Results: {passed} PASSED, {failed} FAILED ===");
        exitCode = failed > 0 ? 1 : 0;
    });
    staThread.SetApartmentState(ApartmentState.STA);
    staThread.Start();
    staThread.Join();
    Environment.ExitCode = exitCode;
    return;
}

// Tray mode: separate process communicating with Service via Named Pipe.
// No DI container needed -- TrayApplication creates PipeClient internally.
// Must run on STA thread — ContextMenuStrip requires STA for COM message pump.
if (args.Contains("--tray"))
{
    var staThread = new Thread(() =>
    {
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        // Enable auto-start on first manual launch
        KaderoAgent.Util.AutoStartHelper.EnableAutoStart();

        Application.Run(new KaderoAgent.Tray.TrayApplication());
    });
    staThread.SetApartmentState(ApartmentState.STA);
    staThread.Start();
    staThread.Join();
    return;
}

// Use exe directory as content root (not the working directory),
// so appsettings.json is always found regardless of how the process was launched
// (e.g. from HKCU\Run, schtasks, or Windows Service).
var builder = Host.CreateApplicationBuilder(new HostApplicationBuilderSettings
{
    Args = args,
    ContentRootPath = AppContext.BaseDirectory
});

// Configuration
builder.Services.Configure<AgentConfig>(builder.Configuration.GetSection("Agent"));

// Add log4net as logging provider (if config file exists)
var log4netConfigPath = Path.Combine(AppContext.BaseDirectory, "log4net.config");
if (File.Exists(log4netConfigPath))
{
    builder.Logging.AddLog4Net(log4netConfigPath);
}

// Core services
builder.Services.AddSingleton<CredentialStore>();
builder.Services.AddSingleton<TokenStore>();
builder.Services.AddSingleton<AuthManager>();
builder.Services.AddSingleton<ApiClient>();
builder.Services.AddSingleton<LocalDatabase>();
builder.Services.AddSingleton<SegmentFileManager>();
builder.Services.AddSingleton<MetricsCollector>();
builder.Services.AddSingleton<SessionManager>();
builder.Services.AddSingleton<SegmentUploader>();
builder.Services.AddSingleton<UploadQueue>();
builder.Services.AddSingleton<ScreenCaptureManager>();
builder.Services.AddSingleton<CommandHandler>();

// Audit: session watcher, process watcher, event sink
builder.Services.AddSingleton<AuditEventSink>();
builder.Services.AddSingleton<IAuditEventSink>(sp => sp.GetRequiredService<AuditEventSink>());
builder.Services.AddSingleton<SessionWatcher>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<AuditEventSink>());
builder.Services.AddHostedService(sp => sp.GetRequiredService<SessionWatcher>());
builder.Services.AddHostedService<ProcessWatcher>();

// Activity tracking: user session info, focus interval sink, active window tracker
builder.Services.AddSingleton<UserSessionInfo>();
builder.Services.AddSingleton<FocusIntervalSink>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<FocusIntervalSink>());
builder.Services.AddHostedService<ActiveWindowTracker>();

// IPC: status provider and command executor (available in all modes)
builder.Services.AddSingleton<AgentStatusProvider>();
builder.Services.AddSingleton<IStatusProvider>(sp => sp.GetRequiredService<AgentStatusProvider>());
builder.Services.AddSingleton<AgentCommandExecutor>();
builder.Services.AddSingleton<KaderoAgent.Ipc.ICommandExecutor>(sp => sp.GetRequiredService<AgentCommandExecutor>());

// Background services
builder.Services.AddHostedService<HeartbeatService>();
builder.Services.AddHostedService<AgentService>();

// Named Pipe server — always start so --tray UI can connect
builder.Services.AddHostedService<PipeServer>();

// Windows Service support (when running as sc.exe registered service)
if (args.Contains("--service"))
{
    builder.Services.AddWindowsService(options =>
    {
        options.ServiceName = "KaderoAgent";
    });
}

var host = builder.Build();

// Headless registration:
//   KaderoAgent --register --server-url=<url> --token=<token>
// Performs device registration via API and saves credentials.
// With --save-only flag, just saves pending registration for SetupForm.
if (args.Contains("--register"))
{
    AttachConsole(ATTACH_PARENT_PROCESS);

    var serverUrl = args.FirstOrDefault(a => a.StartsWith("--server-url="))?.Split('=', 2)[1];
    var token = args.FirstOrDefault(a => a.StartsWith("--token="))?.Split('=', 2)[1];

    if (string.IsNullOrEmpty(serverUrl) || string.IsNullOrEmpty(token))
    {
        Console.WriteLine("Usage: KaderoAgent --register --server-url=<url> --token=<token> [--save-only]");
        return;
    }

    if (args.Contains("--save-only"))
    {
        // Just save for later completion via SetupForm
        Console.WriteLine($"Saving server configuration: {serverUrl}");
        var credStore = host.Services.GetRequiredService<CredentialStore>();
        credStore.SavePendingRegistration(serverUrl, token);
        KaderoAgent.Util.AutoStartHelper.EnableAutoStart();
        Console.WriteLine("Configuration saved. Complete registration via Setup Form or Tray application.");
        return;
    }

    // Full headless registration via API
    try
    {
        Console.WriteLine($"Registering device at {serverUrl} ...");
        var authManager = host.Services.GetRequiredService<KaderoAgent.Auth.AuthManager>();
        var response = await authManager.RegisterAsync(serverUrl, token);
        KaderoAgent.Util.AutoStartHelper.EnableAutoStart();
        Console.WriteLine($"Device registered successfully: {response.DeviceId}");
        Console.WriteLine("Auto-start enabled. Run KaderoAgent.exe to start the agent service.");
    }
    catch (Exception ex)
    {
        Console.WriteLine($"Registration failed: {ex.Message}");
        Environment.ExitCode = 1;
    }
    return;
}

// If --setup flag or no credentials, show setup form (not in service mode)
// Must run on STA thread — WinForms requires STA for proper COM interop.
if (!args.Contains("--service") &&
    (args.Contains("--setup") || !host.Services.GetRequiredService<CredentialStore>().HasCredentials()))
{
    var setupCompleted = false;
    var staThread = new Thread(() =>
    {
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        var form = new KaderoAgent.Tray.SetupForm(host.Services);
        Application.Run(form);
        setupCompleted = host.Services.GetRequiredService<CredentialStore>().HasCredentials();
    });
    staThread.SetApartmentState(ApartmentState.STA);
    staThread.Start();
    staThread.Join();

    // After setup, if credentials saved, enable auto-start and continue
    if (!setupCompleted)
        return;

    // Enable tray auto-start after successful setup
    KaderoAgent.Util.AutoStartHelper.EnableAutoStart();

    // Launch tray UI process so user sees the tray icon immediately
    var exePath = System.Diagnostics.Process.GetCurrentProcess().MainModule?.FileName;
    if (exePath != null)
    {
        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
        {
            FileName = exePath,
            Arguments = "--tray",
            UseShellExecute = true // Must use shell to properly register NotifyIcon with explorer
        });
    }
}

// Launch tray UI for interactive sessions (not when running as Windows Service)
if (!args.Contains("--service"))
{
    var trayExePath = System.Diagnostics.Process.GetCurrentProcess().MainModule?.FileName;
    if (trayExePath != null)
    {
        // Check if tray is already running
        var trayAlreadyRunning = System.Diagnostics.Process.GetProcessesByName("KaderoAgent")
            .Any(p => p.Id != Environment.ProcessId);

        if (!trayAlreadyRunning)
        {
            try
            {
                System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
                {
                    FileName = trayExePath,
                    Arguments = "--tray",
                    UseShellExecute = true // Must use shell to properly register NotifyIcon with explorer
                });
            }
            catch { /* non-fatal: tray is optional */ }
        }
    }
}

// Initialize FocusIntervalSink with current username
{
    var userSessionInfo = host.Services.GetRequiredService<UserSessionInfo>();
    var focusSink = host.Services.GetRequiredService<FocusIntervalSink>();
    var username = userSessionInfo.GetCurrentUsername();
    focusSink.SetUsername(username);
}

// Ensure only one agent host runs at a time (Windows Service OR standalone).
// If service is already running, this process should not start another host.
using var hostMutex = new Mutex(false, @"Global\KaderoAgentHost", out var isFirstInstance);
if (!isFirstInstance && !args.Contains("--service"))
{
    // Service already running. Tray was launched above. Exit gracefully.
    return;
}

await host.RunAsync();
