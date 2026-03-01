# Native Screen Capture Library (prg_capture.dll)

## Overview

Native C++ library for high-performance screen capture on Windows using:
- **DXGI Desktop Duplication API** for screen frame acquisition
- **MediaFoundation H.264 Encoder** for hardware-accelerated video encoding
- **Custom fMP4 Muxer** for fragmented MP4 output

## Status

This directory contains a placeholder for the native capture library.
For MVP, the agent uses FFmpeg-based capture (FfmpegCapture.java) as a fallback.

## Build Requirements

- Visual Studio 2022 with C++ Desktop workload
- CMake 3.25+
- Windows SDK 10.0+

## Build Instructions

```powershell
cd windows-agent/native-build
cmake -B build -S ../src/main/native -G "Visual Studio 17 2022"
cmake --build build --config Release
```

## Output

`prg_capture.dll` - Place in the agent's working directory or on java.library.path.

## JNI Interface

See `com.prg.agent.capture.NativeBridge` for the Java-side JNI declarations.

## Architecture

```
DXGI Desktop Duplication
         |
         v
   D3D11 Texture (GPU)
         |
         v
   MediaFoundation MFT
   (H.264 Baseline)
         |
         v
   fMP4 Muxer
   (moof + mdat)
         |
         v
   Segment Files (.mp4)
```
