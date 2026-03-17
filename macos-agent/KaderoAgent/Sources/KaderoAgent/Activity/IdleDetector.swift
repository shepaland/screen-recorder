import Foundation
import CoreGraphics

public final class IdleDetector {
    public var idleThresholdSec: Double = 300
    public init(idleThresholdSec: Double = 300) { self.idleThresholdSec = idleThresholdSec }

    public var secondsSinceLastInput: Double {
        CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: .mouseMoved)
    }
    public var isIdle: Bool { secondsSinceLastInput >= idleThresholdSec }
}
