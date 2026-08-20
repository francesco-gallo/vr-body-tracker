# VR Body Tracker (OSC)

VR Body Tracker is an Android application designed for real-time 3D pose estimation and streaming to VR environments (like VRChat) via the Open Sound Control (OSC) protocol. It leverages Google ML Kit's on-device pose detection to provide a low-latency, cable-free Full Body Tracking (FBT) alternative using just your smartphone camera.
Almost all of this has been vibe-coded (read as: totally made with AI. This readme included.)

## 🚀 Features

-   **On-Device AI Tracking**: Powered by ML Kit Pose Detection (beta5) for robust 3D skeletal tracking without external servers.
-   **VRChat Native Integration**: Direct mapping to VRChat's `/tracking/trackers` schema, including Head, Hip, Chest, Elbows, Knees, and Feet.
-   **OSC Networking**: High-performance UDP streaming with support for **OSC Bundles** to minimize network packets.
-   **Neutral Pose Calibration**: One-tap T-Pose/I-Pose calibration to establish user-specific skeletal offsets.
-   **Advanced Signal Processing**:
    -   **Exponential Moving Average (EMA)** smoothing to eliminate jitter.
    -   **Dynamic Scaling**: Automatic height estimation and world-space mapping based on user input.
-   **Debug & Visualization**:
    -   Real-time skeleton overlay with joint axis visualization.
    -   **MJPEG Web Server**: Integrated server (port 8080) providing a remote MJPEG stream of the tracking view for monitoring on a PC.
-   **Camera Control**: 
    -   Support for 60 FPS capture via Camera2 interoperability.
    -   Front/Back camera toggle with optional horizontal mirroring.
-   **Persistent Config**: Automatically saves IP, port, height, and smoothing settings.

## 📱 Hardware & Software Requirements

-   **Android Version**: API 26 (Android 8.0) or higher.
-   **Camera**: Recommended device with "FULL" or "LEVEL_3" hardware support for optimal frame rates.
-   **Network**: Smartphone and VR headset/PC must be on the same local network.

## 🛠️ OSC Tracker Mapping

The following trackers are transmitted to the `/tracking/trackers/{id}/` path:

## 📡 Tracked OSC Endpoints

The application maps and broadcasts data to the following 9 VRChat-compliant OSC tracker addresses:

| Tracker Name | Position OSC Address | Rotation OSC Address |
| :--- | :--- | :--- |
| **Head** | `/tracking/trackers/head/position` | `/tracking/trackers/head/rotation` |
| **Hip** *(Centered)* | `/tracking/trackers/hip/position` | `/tracking/trackers/hip/rotation` |
| **Chest** *(Centered)* | `/tracking/trackers/chest/position` | `/tracking/trackers/chest/rotation` |
| **Left Elbow** | `/tracking/trackers/left_elbow/position` | `/tracking/trackers/left_elbow/rotation` |
| **Right Elbow** | `/tracking/trackers/right_elbow/position` | `/tracking/trackers/right_elbow/rotation` |
| **Left Knee** | `/tracking/trackers/left_knee/position` | `/tracking/trackers/left_knee/rotation` |
| **Right Knee** | `/tracking/trackers/right_knee/position` | `/tracking/trackers/right_knee/rotation` |
| **Left Foot** | `/tracking/trackers/left_foot/position` | `/tracking/trackers/left_foot/rotation` |
| **Right Foot** | `/tracking/trackers/right_foot/position` | `/tracking/trackers/right_foot/rotation` |

## 📖 How to Use

1.  **Launch & Permissions**: Grant camera and internet permissions.
2.  **Configuration**:
    *   **IP Address**: Enter your PC or Quest's local IP.
    *   **Port**: Default is `9000` for VRChat.
    *   **User Height**: Enter your height in meters for accurate tracker placement.
3.  **Calibration**:
    *   Tap **Calibrate Neutral Pose**.
    *   Wait for the 5-second countdown.
    *   Stand in a neutral pose (T-Pose or I-Pose) facing the camera.
4.  **Streaming**:
    *   Tap **Start Stream**.
    *   Enable OSC in VRChat (Action Menu > Expressions > Options > OSC > Enabled).
5.  **Remote Monitoring**: Access `http://[PHONE_IP]:8080` in a web browser to view the processed MJPEG stream. NOTE: THIS WILL DROP THE PERFORMANCES.

If you change the destination port from 9000 to 9002, you can also use the tools/vrchat_bridge.ps1 powershell script to see which values will be sent afterward to 9000

## 🏗️ Project Structure

-   `PoseTracker.kt`: Orchestrates ML Kit analysis and frame conversion.
-   `PoseOscMapper.kt`: Implements the 3D math for coordinate normalization and VRChat protocol mapping.
-   `PoseProcessing.kt`: Handles EMA smoothing and calibration offset logic.
-   `OscSender.kt`: Encodes and transmits raw OSC messages and bundles.
-   `MjpegServer.kt`: A `NanoHTTPD` based server for remote skeletal visualization.
-   `JointOverlayView.kt`: Custom UI component for local skeleton drawing.

## 📄 License

This project is intended for personal and development use.
