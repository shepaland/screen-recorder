package com.prg.agent.capture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JNI bridge to the native C++ screen capture DLL.
 *
 * <p>This is a stub implementation for MVP. The actual native library (prg_capture.dll)
 * will use DXGI Desktop Duplication API for screen capture and MediaFoundation
 * for H.264 encoding. For now, the FFmpeg-based capture is used as a fallback.
 *
 * <p>The native library exports:
 * <ul>
 *   <li>createCapture - Initialize capture pipeline</li>
 *   <li>startCapture - Begin screen recording</li>
 *   <li>stopCapture - Stop recording</li>
 *   <li>getLastSegmentPath - Get path of the most recent completed segment</li>
 *   <li>destroyCapture - Release all resources</li>
 * </ul>
 */
public class NativeBridge {

    private static final Logger log = LoggerFactory.getLogger(NativeBridge.class);
    private static boolean nativeLoaded = false;

    static {
        try {
            System.loadLibrary("prg_capture");
            nativeLoaded = true;
            log.info("Native capture library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            nativeLoaded = false;
            log.warn("Native capture library not available, FFmpeg fallback will be used: {}", e.getMessage());
        }
    }

    /**
     * Returns true if the native capture library was loaded successfully.
     */
    public static boolean isNativeAvailable() {
        return nativeLoaded;
    }

    /**
     * Creates a new capture instance.
     *
     * @param fps        frames per second to capture
     * @param quality    quality level (0=low, 1=medium, 2=high)
     * @param outputPath directory for output segment files
     * @return handle to the capture instance, or 0 on failure
     */
    public static native long createCapture(int fps, int quality, String outputPath);

    /**
     * Starts the capture.
     *
     * @param handle capture instance handle
     * @return true if capture started successfully
     */
    public static native boolean startCapture(long handle);

    /**
     * Stops the capture.
     *
     * @param handle capture instance handle
     * @return true if capture stopped successfully
     */
    public static native boolean stopCapture(long handle);

    /**
     * Returns the file path of the last completed segment.
     *
     * @param handle capture instance handle
     * @return path to the last segment file, or null if none
     */
    public static native String getLastSegmentPath(long handle);

    /**
     * Destroys the capture instance and frees resources.
     *
     * @param handle capture instance handle
     */
    public static native void destroyCapture(long handle);

    /**
     * Returns the last error message from the native library.
     *
     * @param handle capture instance handle
     * @return error message string, or null if no error
     */
    public static native String getLastError(long handle);
}
