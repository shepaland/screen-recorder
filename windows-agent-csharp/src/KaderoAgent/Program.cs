using KaderoAgent.Auth;
using KaderoAgent.Capture;
using KaderoAgent.Command;
using KaderoAgent.Configuration;
using KaderoAgent.Service;
using KaderoAgent.Storage;
using KaderoAgent.Upload;
using KaderoAgent.Util;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

var builder = Host.CreateApplicationBuilder(args);

// Configuration
builder.Services.Configure<AgentConfig>(builder.Configuration.GetSection("Agent"));

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

// Background services
builder.Services.AddHostedService<HeartbeatService>();
builder.Services.AddHostedService<AgentService>();

// Windows Service support
if (args.Contains("--service"))
{
    builder.Services.AddWindowsService(options =>
    {
        options.ServiceName = "KaderoAgent";
    });
}

var host = builder.Build();

// Headless registration: --register --server-url=<url> --token=<token> --username=<user> --password=<pass>
if (args.Contains("--register"))
{
    var serverUrl = args.FirstOrDefault(a => a.StartsWith("--server-url="))?.Split('=', 2)[1];
    var token = args.FirstOrDefault(a => a.StartsWith("--token="))?.Split('=', 2)[1];
    var username = args.FirstOrDefault(a => a.StartsWith("--username="))?.Split('=', 2)[1];
    var password = args.FirstOrDefault(a => a.StartsWith("--password="))?.Split('=', 2)[1];

    if (string.IsNullOrEmpty(serverUrl) || string.IsNullOrEmpty(token)
        || string.IsNullOrEmpty(username) || string.IsNullOrEmpty(password))
    {
        Console.WriteLine("Usage: KaderoAgent --register --server-url=<url> --token=<token> --username=<user> --password=<pass>");
        return;
    }

    Console.WriteLine($"Registering device with server: {serverUrl}");
    var authManager = host.Services.GetRequiredService<AuthManager>();
    try
    {
        var response = await authManager.RegisterAsync(serverUrl, token, username, password);
        Console.WriteLine($"Device registered: {response.DeviceId}");
        Console.WriteLine("Registration successful. Start the service with: sc start KaderoAgent");
    }
    catch (Exception ex)
    {
        Console.WriteLine($"Registration failed: {ex.Message}");
        Environment.Exit(1);
    }
    return;
}

// If --setup flag or no credentials, show setup form
if (args.Contains("--setup") || !host.Services.GetRequiredService<CredentialStore>().HasCredentials())
{
    Application.EnableVisualStyles();
    Application.SetCompatibleTextRenderingDefault(false);
    var form = new KaderoAgent.Tray.SetupForm(host.Services);
    Application.Run(form);

    // After setup, if credentials saved, continue
    if (!host.Services.GetRequiredService<CredentialStore>().HasCredentials())
        return;
}

await host.RunAsync();
