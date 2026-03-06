namespace KaderoAgent.Configuration;

public class AgentConfig
{
    public string ServerUrl { get; set; } = "";
    public string DataPath { get; set; } = @"C:\screen-recorder-agent";
    public int HeartbeatIntervalSec { get; set; } = 30;
    public int SegmentDurationSec { get; set; } = 10;
    public int CaptureFps { get; set; } = 1;           // Default: 1 fps per spec
    public string Quality { get; set; } = "medium";
    public string Resolution { get; set; } = "1280x720"; // Default: 720p per spec
    public int SessionMaxDurationHours { get; set; } = 24; // Default: 24h per spec
    public bool AutoStart { get; set; } = true;           // Auto-start recording on boot
    public long MaxBufferBytes { get; set; } = 2L * 1024 * 1024 * 1024; // 2GB
    public string FfmpegPath { get; set; } = "ffmpeg.exe";
}
