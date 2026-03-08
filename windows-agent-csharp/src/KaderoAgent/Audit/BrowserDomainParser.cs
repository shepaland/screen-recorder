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
        // Edge with optional profile label (e.g. "Личный: Microsoft Edge", "InPrivate: Microsoft Edge")
        ("Edge", new Regex(@"^(.+?)\s*[-\u2013\u2014]\s*(?:[\w\p{L}]+:\s*)?Microsoft\s*\u200B?\s*Edge$", RegexOptions.Compiled)),
        ("Firefox", new Regex(@"^(.+?)\s*[-\u2013\u2014]\s*Mozilla Firefox$", RegexOptions.Compiled)),
        ("Opera", new Regex(@"^(.+?)\s*[-\u2013\u2014]\s*Opera$", RegexOptions.Compiled)),
        ("Yandex Browser", new Regex(@"^(.+?)\s*[-\u2013\u2014]\s*(Yandex|Яндекс)\s*(Browser|Браузер)$", RegexOptions.Compiled)),
        // Brave
        ("Brave", new Regex(@"^(.+?)\s*[-\u2013\u2014]\s*Brave$", RegexOptions.Compiled)),
        // Vivaldi
        ("Vivaldi", new Regex(@"^(.+?)\s*[-\u2013\u2014]\s*Vivaldi$", RegexOptions.Compiled)),
    };

    private static readonly Regex DomainRegex = new(@"\b([a-zA-Z0-9][-a-zA-Z0-9]*\.)+[a-zA-Z]{2,}\b", RegexOptions.Compiled);

    // Regex for URLs in titles (e.g. "https://example.com/path" or "http://...")
    private static readonly Regex UrlInTitleRegex = new(@"https?://([a-zA-Z0-9][-a-zA-Z0-9]*\.)+[a-zA-Z]{2,}[^\s]*", RegexOptions.Compiled | RegexOptions.IgnoreCase);

    /// <summary>
    /// Mapping of well-known page titles to their domains.
    /// Covers the most popular web services whose titles don't contain the domain.
    /// </summary>
    private static readonly Dictionary<string, string> KnownTitleDomains = new(StringComparer.OrdinalIgnoreCase)
    {
        // Search engines
        { "Google", "google.com" },
        { "Яндекс", "ya.ru" },
        { "Bing", "bing.com" },
        { "DuckDuckGo", "duckduckgo.com" },

        // Email
        { "Gmail", "mail.google.com" },
        { "Входящие", "mail.google.com" },
        { "Inbox", "mail.google.com" },
        { "Outlook", "outlook.live.com" },
        { "Почта Mail.ru", "mail.ru" },
        { "Яндекс Почта", "mail.yandex.ru" },

        // Social
        { "ВКонтакте", "vk.com" },
        { "VK", "vk.com" },
        { "Telegram Web", "web.telegram.org" },
        { "WhatsApp", "web.whatsapp.com" },
        { "WhatsApp Web", "web.whatsapp.com" },
        { "Discord", "discord.com" },
        { "Slack", "slack.com" },
        { "Facebook", "facebook.com" },
        { "Instagram", "instagram.com" },
        { "X", "x.com" },
        { "Twitter", "x.com" },
        { "LinkedIn", "linkedin.com" },
        { "Reddit", "reddit.com" },

        // Video/Media
        { "YouTube", "youtube.com" },
        { "YouTube Music", "music.youtube.com" },
        { "Twitch", "twitch.tv" },
        { "Netflix", "netflix.com" },
        { "Кинопоиск", "kinopoisk.ru" },

        // AI / Tools
        { "Claude", "claude.ai" },
        { "ChatGPT", "chatgpt.com" },
        { "Gemini", "gemini.google.com" },
        { "Notion", "notion.so" },
        { "Figma", "figma.com" },
        { "Miro", "miro.com" },
        { "Trello", "trello.com" },

        // Dev
        { "GitHub", "github.com" },
        { "GitLab", "gitlab.com" },
        { "Stack Overflow", "stackoverflow.com" },
        { "Stack Exchange", "stackexchange.com" },
        { "Bitbucket", "bitbucket.org" },

        // Office / Docs
        { "Google Docs", "docs.google.com" },
        { "Google Sheets", "docs.google.com" },
        { "Google Slides", "docs.google.com" },
        { "Google Drive", "drive.google.com" },
        { "Google Meet", "meet.google.com" },
        { "Google Calendar", "calendar.google.com" },
        { "Google Maps", "maps.google.com" },

        // Shopping
        { "Amazon", "amazon.com" },
        { "Wildberries", "wildberries.ru" },
        { "OZON", "ozon.ru" },
        { "AliExpress", "aliexpress.com" },

        // Cloud / Services
        { "Jira", "atlassian.net" },
        { "Confluence", "atlassian.net" },
        { "Yandex Cloud", "console.yandex.cloud" },
        { "AWS", "console.aws.amazon.com" },
        { "Azure", "portal.azure.com" },

        // News
        { "Habr", "habr.com" },
        { "Хабр", "habr.com" },
        { "Wikipedia", "wikipedia.org" },
        { "Википедия", "ru.wikipedia.org" },
    };

    public static bool IsBrowser(string processName)
    {
        var name = Path.GetFileNameWithoutExtension(processName);
        return BrowserProcesses.Contains(name);
    }

    /// <summary>
    /// Parse browser window title to extract browser name and domain.
    /// Uses multiple strategies:
    /// 1. Regex to extract page title from browser window title
    /// 2. URL detection in the title text
    /// 3. Known title-to-domain mapping
    /// 4. Domain regex extraction from title
    /// </summary>
    public static (string? BrowserName, string? Domain) ParseTitle(string windowTitle, string processName)
    {
        if (string.IsNullOrEmpty(windowTitle)) return (null, null);

        foreach (var (browserName, pattern) in Patterns)
        {
            var match = pattern.Match(windowTitle);
            if (match.Success)
            {
                var pageTitle = match.Groups[1].Value.Trim();
                var domain = ResolveDomain(pageTitle);
                return (browserName, domain);
            }
        }

        // Fallback: if process is a known browser, try to extract domain from full title
        var baseName = Path.GetFileNameWithoutExtension(processName);
        if (BrowserProcesses.Contains(baseName))
        {
            var domain = ResolveDomain(windowTitle);
            return (baseName, domain);
        }

        return (null, null);
    }

    /// <summary>
    /// Multi-strategy domain resolution from a page title string.
    /// </summary>
    private static string? ResolveDomain(string text)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;

        // Strategy 1: Check for URLs in the text (e.g. "https://example.com/...")
        var urlMatch = UrlInTitleRegex.Match(text);
        if (urlMatch.Success)
        {
            var domain = BrowserUrlExtractor.ExtractDomainFromUrl(urlMatch.Value);
            if (domain != null) return domain;
        }

        // Strategy 2: Known title mapping (exact match on trimmed title)
        // Also try the part before " - " separator (e.g. "Gmail - user@gmail.com")
        if (KnownTitleDomains.TryGetValue(text, out var knownDomain))
            return knownDomain;

        // Try first part before common separators
        var separatorIdx = text.IndexOfAny(new[] { '-', '\u2013', '\u2014', '|', ':' });
        if (separatorIdx > 0)
        {
            var firstPart = text[..separatorIdx].Trim();
            if (KnownTitleDomains.TryGetValue(firstPart, out var knownDomain2))
                return knownDomain2;

            // Also try last part (e.g. "Some page - GitHub")
            var lastPart = text[(separatorIdx + 1)..].Trim();
            if (KnownTitleDomains.TryGetValue(lastPart, out var knownDomain3))
                return knownDomain3;
        }

        // Strategy 3: Domain regex extraction (works when title contains a domain string)
        return ExtractDomain(text);
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
