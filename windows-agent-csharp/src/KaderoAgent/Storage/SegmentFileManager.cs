using KaderoAgent.Configuration;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace KaderoAgent.Storage;

public class SegmentFileManager
{
    private readonly IOptions<AgentConfig> _config;
    private readonly ILogger<SegmentFileManager> _logger;

    public SegmentFileManager(IOptions<AgentConfig> config, ILogger<SegmentFileManager> logger)
    {
        _config = config;
        _logger = logger;
    }

    public void EvictOldSegments()
    {
        var dir = Path.Combine(_config.Value.DataPath, "segments");
        if (!Directory.Exists(dir)) return;

        var totalSize = Directory.GetFiles(dir, "*.mp4", SearchOption.AllDirectories)
            .Sum(f => new FileInfo(f).Length);

        if (totalSize <= _config.Value.MaxBufferBytes) return;

        var files = Directory.GetFiles(dir, "*.mp4", SearchOption.AllDirectories)
            .Select(f => new FileInfo(f))
            .OrderBy(f => f.CreationTimeUtc)
            .ToList();

        while (totalSize > _config.Value.MaxBufferBytes && files.Count > 0)
        {
            var oldest = files[0];
            totalSize -= oldest.Length;
            try { oldest.Delete(); } catch { }
            files.RemoveAt(0);
        }

        _logger.LogInformation("Evicted old segments, current buffer: {Size}MB", totalSize / 1024 / 1024);
    }
}
