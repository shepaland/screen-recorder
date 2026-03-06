namespace KaderoAgent.Configuration;

public class AgentConfig
{
    public string ServerUrl { get; set; } = "";
    public string DataPath { get; set; } = @"C:\screen-recorder-agent";
    public int HeartbeatIntervalSec { get; set; } = 30;
    public int SegmentDurationSec { get; set; } = 10;
    public int CaptureFps { get; set; } = 5;
    public string Quality { get; set; } = "medium";
    public long MaxBufferBytes { get; set; } = 2L * 1024 * 1024 * 1024; // 2GB
    public string FfmpegPath { get; set; } = "ffmpeg.exe";
}
