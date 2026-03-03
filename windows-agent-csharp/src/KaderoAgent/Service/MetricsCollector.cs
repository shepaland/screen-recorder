using System.Diagnostics;
using Microsoft.Extensions.Logging;

namespace KaderoAgent.Service;

public class MetricsCollector
{
    private readonly ILogger<MetricsCollector> _logger;

    public MetricsCollector(ILogger<MetricsCollector> logger)
    {
        _logger = logger;
    }

    public double GetCpuUsage()
    {
        try
        {
            using var cpuCounter = new PerformanceCounter("Processor", "% Processor Time", "_Total");
            cpuCounter.NextValue();
            Thread.Sleep(100);
            return Math.Round(cpuCounter.NextValue(), 1);
        }
        catch { return 0; }
    }

    public double GetMemoryUsageMb()
    {
        try
        {
            var process = Process.GetCurrentProcess();
            return Math.Round(process.WorkingSet64 / 1024.0 / 1024.0, 1);
        }
        catch { return 0; }
    }

    public double GetDiskFreeGb()
    {
        try
        {
            var drive = new DriveInfo(Path.GetPathRoot(Environment.SystemDirectory) ?? "C:\\");
            return Math.Round(drive.AvailableFreeSpace / 1024.0 / 1024.0 / 1024.0, 1);
        }
        catch { return 0; }
    }
}
