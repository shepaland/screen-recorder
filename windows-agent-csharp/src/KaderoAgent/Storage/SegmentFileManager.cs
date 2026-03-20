using KaderoAgent.Configuration;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace KaderoAgent.Storage;

public class SegmentFileManager
{
    private readonly IOptions<AgentConfig> _config;
    private readonly LocalDatabase _db;
    private readonly ILogger<SegmentFileManager> _logger;

    public SegmentFileManager(IOptions<AgentConfig> config, LocalDatabase db, ILogger<SegmentFileManager> logger)
    {
        _config = config;
        _db = db;
        _logger = logger;
    }

    public void EvictOldSegments()
    {
        var dir = Path.Combine(_config.Value.DataPath, "segments");
        if (!Directory.Exists(dir)) return;

        long totalSize = 0;
        foreach (var f in Directory.GetFiles(dir, "*.mp4", SearchOption.AllDirectories))
        {
            try { totalSize += new FileInfo(f).Length; } catch { }
        }

        if (totalSize <= _config.Value.MaxBufferBytes) return;

        // 1. First evict SERVER_SIDE_DONE (already on server)
        var doneSegments = _db.GetSegmentsForEviction()
            .Where(s => s.Status == "SERVER_SIDE_DONE" || s.Status == "SENDED")
            .OrderBy(s => s.CreatedTs)
            .ToList();

        foreach (var seg in doneSegments)
        {
            DeleteFile(seg.FilePath);
            _db.UpdateSegmentStatus(seg.Id, "EVICTED");
            totalSize -= seg.SizeBytes;
            if (totalSize <= _config.Value.MaxBufferBytes) return;
        }

        // 2. Then evict NEW/PENDING (data not on server — WARNING)
        var pendingSegments = _db.GetSegmentsForEviction();
        // GetSegmentsForEviction returns SERVER_SIDE_DONE and SENDED, need unsent too
        var allFiles = Directory.GetFiles(dir, "*.mp4", SearchOption.AllDirectories)
            .Select(f => new FileInfo(f))
            .OrderBy(f => f.CreationTimeUtc)
            .ToList();

        foreach (var file in allFiles)
        {
            if (totalSize <= _config.Value.MaxBufferBytes) return;

            _logger.LogWarning("Evicting segment due to disk pressure: {Path}", file.FullName);
            try { file.Delete(); } catch { }
            totalSize -= file.Length;
        }

        _logger.LogInformation("Evicted old segments, current buffer: {Size}MB", totalSize / 1024 / 1024);
    }

    private static void DeleteFile(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }
}
