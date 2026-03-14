namespace KaderoAgent.Audit;

/// <summary>
/// Represents a user input event (mouse click, keyboard metric, scroll, clipboard).
/// Collected in Tray process, uploaded by Service via InputEventSink.
/// </summary>
public class InputEvent
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string EventType { get; set; } = ""; // "mouse_click", "keyboard_metric", "scroll", "clipboard"
    public DateTime EventTs { get; set; }
    public DateTime? EventEndTs { get; set; }

    // Mouse click fields
    public int? ClickX { get; set; }
    public int? ClickY { get; set; }
    public string? ClickButton { get; set; } // "left", "right", "middle"
    public string? ClickType { get; set; } // "single", "double"

    // UI element context
    public string? UiElementType { get; set; }
    public string? UiElementName { get; set; }

    // Keyboard metric fields
    public int? KeystrokeCount { get; set; }
    public bool? HasTypingBurst { get; set; }

    // Scroll fields
    public string? ScrollDirection { get; set; }
    public int? ScrollTotalDelta { get; set; }
    public int? ScrollEventCount { get; set; }

    // Context
    public string? ProcessName { get; set; }
    public string? WindowTitle { get; set; }

    // Video timecode binding
    public string? SegmentId { get; set; }
    public int? SegmentOffsetMs { get; set; }

    // Recording session
    public string? SessionId { get; set; }
}
