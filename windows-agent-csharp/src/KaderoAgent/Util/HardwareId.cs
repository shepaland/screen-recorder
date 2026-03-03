using System.Management;
using System.Security.Cryptography;
using System.Text;

namespace KaderoAgent.Util;

public static class HardwareId
{
    public static string Generate()
    {
        var sb = new StringBuilder();
        sb.Append(GetWmiValue("Win32_BaseBoard", "SerialNumber"));
        sb.Append(GetWmiValue("Win32_Processor", "ProcessorId"));
        sb.Append(GetWmiValue("Win32_DiskDrive", "SerialNumber"));

        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(sb.ToString()));
        return Convert.ToHexString(hash).ToLower();
    }

    private static string GetWmiValue(string className, string propertyName)
    {
        try
        {
            using var searcher = new ManagementObjectSearcher($"SELECT {propertyName} FROM {className}");
            foreach (var obj in searcher.Get())
            {
                return obj[propertyName]?.ToString() ?? "";
            }
        }
        catch { }
        return "unknown";
    }
}
