using System.Windows.Automation;

namespace KaderoAgent.Audit;

/// <summary>
/// Extracts the current URL from a browser window using Windows UI Automation API.
/// Works with Chromium-based browsers (Chrome, Edge, Brave, Vivaldi, Yandex Browser)
/// and Firefox by finding the address bar element in the accessibility tree.
///
/// This runs in the tray process (user session), where UI Automation works correctly.
/// Session 0 (Windows Service) cannot access UI elements.
/// </summary>
public static class BrowserUrlExtractor
{
    private static readonly log4net.ILog _log = log4net.LogManager.GetLogger(typeof(BrowserUrlExtractor));

    // Cache to avoid repeated slow lookups for the same window
    private static IntPtr _cachedHwnd;
    private static string? _cachedUrl;
    private static DateTime _cachedAt;
    private static readonly TimeSpan CacheTtl = TimeSpan.FromSeconds(10);

    // Diagnostic: log first N calls at INFO level
    private static int _diagnosticCallCount;
    private const int DiagnosticLogLimit = 20;

    /// <summary>
    /// Timeout for UI Automation FindAll call (can be slow on complex browser windows).
    /// </summary>
    private const int FindAllTimeoutMs = 5_000;

    /// <summary>
    /// Invalidate the URL cache (call when tab changes within the same browser window).
    /// </summary>
    public static void InvalidateCache()
    {
        _cachedHwnd = IntPtr.Zero;
        _cachedUrl = null;
    }

    /// <summary>
    /// Get the URL from the browser address bar using UI Automation.
    /// Returns null if URL cannot be extracted.
    /// Caches result per hwnd for 10 seconds to avoid repeated slow UI Automation calls.
    /// </summary>
    public static string? GetUrl(IntPtr hwnd)
    {
        if (hwnd == IntPtr.Zero) return null;

        // Check cache
        if (hwnd == _cachedHwnd && DateTime.UtcNow - _cachedAt < CacheTtl)
            return _cachedUrl;

        string? url = null;
        try
        {
            url = ExtractUrlFromAddressBar(hwnd);
        }
        catch (Exception ex)
        {
            _log.Warn($"BrowserUrlExtractor error for hwnd {hwnd}: {ex.GetType().Name}: {ex.Message}");
        }

        // Update cache
        _cachedHwnd = hwnd;
        _cachedUrl = url;
        _cachedAt = DateTime.UtcNow;

        return url;
    }

    /// <summary>
    /// Extract domain from a URL string.
    /// Handles both full URLs ("https://example.com/path") and bare domains ("example.com").
    /// </summary>
    public static string? ExtractDomainFromUrl(string? url)
    {
        if (string.IsNullOrWhiteSpace(url)) return null;

        url = url.Trim();

        // Try as full URL first
        if (Uri.TryCreate(url, UriKind.Absolute, out var uri) &&
            (uri.Scheme == "http" || uri.Scheme == "https"))
        {
            var host = uri.Host.ToLowerInvariant();
            if (!string.IsNullOrEmpty(host) && host.Contains('.'))
                return host;
        }

        // Try prepending https:// (address bar often shows just "example.com/path")
        if (!url.Contains("://") && Uri.TryCreate("https://" + url, UriKind.Absolute, out var uri2))
        {
            var host = uri2.Host.ToLowerInvariant();
            if (!string.IsNullOrEmpty(host) && host.Contains('.'))
                return host;
        }

        return null;
    }

    // ── Private implementation ───────────────────────────────────────────────

    private static string? ExtractUrlFromAddressBar(IntPtr hwnd)
    {
        bool isDiag = _diagnosticCallCount < DiagnosticLogLimit;
        if (isDiag) Interlocked.Increment(ref _diagnosticCallCount);

        AutomationElement? element;
        try
        {
            element = AutomationElement.FromHandle(hwnd);
        }
        catch (Exception ex)
        {
            _log.Warn($"AutomationElement.FromHandle failed for hwnd {hwnd}: {ex.Message}");
            return null;
        }

        if (element == null)
        {
            if (isDiag) _log.Info($"BrowserUrlExtractor: FromHandle returned null for hwnd {hwnd}");
            return null;
        }

        // Find all Edit controls with timeout (can be very slow on complex browser windows)
        var editCondition = new PropertyCondition(
            AutomationElement.ControlTypeProperty, ControlType.Edit);

        AutomationElementCollection? edits = null;
        try
        {
            var task = Task.Run(() => element.FindAll(TreeScope.Descendants, editCondition));
            if (!task.Wait(FindAllTimeoutMs))
            {
                _log.Warn($"BrowserUrlExtractor: FindAll timed out after {FindAllTimeoutMs}ms for hwnd {hwnd}");
                return null;
            }
            edits = task.Result;
        }
        catch (AggregateException ae)
        {
            _log.Warn($"BrowserUrlExtractor: FindAll failed: {ae.InnerException?.Message ?? ae.Message}");
            return null;
        }

        if (edits == null || edits.Count == 0)
        {
            if (isDiag) _log.Info($"BrowserUrlExtractor: No Edit controls found in hwnd {hwnd}");
            return null;
        }

        if (isDiag) _log.Info($"BrowserUrlExtractor: Found {edits.Count} Edit controls in hwnd {hwnd}");

        // Iterate edit controls looking for the address bar
        int limit = Math.Min(edits.Count, 20);
        for (int i = 0; i < limit; i++)
        {
            var edit = edits[i];
            try
            {
                string className = edit.Current.ClassName ?? "";
                string automationId = edit.Current.AutomationId ?? "";
                string name = edit.Current.Name ?? "";

                if (isDiag)
                    _log.Info($"BrowserUrlExtractor: Edit[{i}] class='{className}' id='{automationId}' name='{name}'");

                bool isAddressBar = false;

                // Chromium-based browsers (Chrome, Edge, Brave, Vivaldi, Yandex Browser)
                if (className.Contains("OmniboxViewViews", StringComparison.OrdinalIgnoreCase))
                    isAddressBar = true;
                // Firefox
                else if (automationId.Equals("urlbar-input", StringComparison.OrdinalIgnoreCase))
                    isAddressBar = true;
                // Generic fallbacks (localized names)
                else if (name.Contains("Address", StringComparison.OrdinalIgnoreCase) ||
                         name.Contains("URL", StringComparison.OrdinalIgnoreCase) ||
                         name.Contains("address and search", StringComparison.OrdinalIgnoreCase) ||
                         name.Contains("Адрес", StringComparison.OrdinalIgnoreCase) ||
                         name.Contains("адресн", StringComparison.OrdinalIgnoreCase) ||
                         name.Contains("Строка", StringComparison.OrdinalIgnoreCase))
                    isAddressBar = true;

                if (isAddressBar)
                {
                    // Get the text value from the address bar
                    if (edit.TryGetCurrentPattern(ValuePattern.Pattern, out var patternObj))
                    {
                        var valuePattern = (ValuePattern)patternObj;
                        var value = valuePattern.Current.Value;
                        if (!string.IsNullOrWhiteSpace(value))
                        {
                            _log.Info($"BrowserUrlExtractor: Found URL '{value}' (class={className}, id={automationId}, name={name})");
                            return value;
                        }
                        else if (isDiag)
                        {
                            _log.Info($"BrowserUrlExtractor: Address bar found but value is empty (class={className})");
                        }
                    }
                    else if (isDiag)
                    {
                        _log.Info($"BrowserUrlExtractor: Address bar found but no ValuePattern (class={className})");
                    }
                }
            }
            catch (ElementNotAvailableException)
            {
                // Element may have been removed from the tree (tab closed, etc.)
                continue;
            }
        }

        if (isDiag) _log.Info($"BrowserUrlExtractor: No address bar found among {limit} edit controls");
        return null;
    }
}
