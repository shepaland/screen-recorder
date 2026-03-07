using System.Text.RegularExpressions;

namespace KaderoAgent.Audit;

public static class BrowserDomainParser
{
    private static readonly HashSet<string> BrowserProcesses = new(StringComparer.OrdinalIgnoreCase)
    {
        "chrome", "msedge", "firefox", "opera", "browser", "vivaldi", "brave"
    };

    private static readonly List<(string BrowserName, Regex Pattern)> Patterns = new()
    {
        ("Chrome", new Regex(@"^(.+?)\s*[-\u2013\u2014]\s*Google Chrome$", RegexOptions.Compiled)),
        ("Edge", new Regex(@"^(.+?)\s*[-\u2013\u2014\u200B]\s*Microsoft\s*\u200B?\s*Edge$", RegexOptions.Compiled)),
        ("Firefox", new Regex(@"^(.+?)\s*[-\u2013\u2014]\s*Mozilla Firefox$", RegexOptions.Compiled)),
        ("Opera", new Regex(@"^(.+?)\s*[-\u2013\u2014]\s*Opera$", RegexOptions.Compiled)),
        ("Yandex Browser", new Regex(@"^(.+?)\s*[-\u2013\u2014]\s*(Yandex|Яндекс\s*Браузер)$", RegexOptions.Compiled)),
    };

    private static readonly Regex DomainRegex = new(@"\b([a-zA-Z0-9][-a-zA-Z0-9]*\.)+[a-zA-Z]{2,}\b", RegexOptions.Compiled);

    public static bool IsBrowser(string processName)
    {
        var name = Path.GetFileNameWithoutExtension(processName);
        return BrowserProcesses.Contains(name);
    }

    public static (string? BrowserName, string? Domain) ParseTitle(string windowTitle, string processName)
    {
        if (string.IsNullOrEmpty(windowTitle)) return (null, null);

        foreach (var (browserName, pattern) in Patterns)
        {
            var match = pattern.Match(windowTitle);
            if (match.Success)
            {
                var pageTitle = match.Groups[1].Value.Trim();
                var domain = ExtractDomain(pageTitle);
                return (browserName, domain);
            }
        }

        // Fallback: if process is a known browser, try to extract domain from full title
        var baseName = Path.GetFileNameWithoutExtension(processName);
        if (BrowserProcesses.Contains(baseName))
        {
            var domain = ExtractDomain(windowTitle);
            return (baseName, domain);
        }

        return (null, null);
    }

    private static string? ExtractDomain(string text)
    {
        var match = DomainRegex.Match(text);
        if (match.Success)
        {
            var candidate = match.Value.ToLowerInvariant();
            // Filter out common false positives
            if (!candidate.Contains("localhost") && !IsIpAddress(candidate))
                return candidate;
        }
        return null;
    }

    private static bool IsIpAddress(string s) =>
        System.Net.IPAddress.TryParse(s, out _);
}
