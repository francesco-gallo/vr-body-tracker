# VR Body Tracker (OSC)

VR Body Tracker is an Android application designed for real-time 3D pose estimation and streaming to VR environments (like VRChat) via the Open Sound Control (OSC) protocol. It leverages advanced on-device AI to provide a low-latency, cable-free Full Body Tracking (FBT) alternative using just your smartphone camera.
Almost all of this has been vibe-coded (read as: totally made with AI. This readme included.)

## 🚀 Features

-   **Multi-Engine AI Tracking**: 
    -   **Google ML Kit**: Reliable and efficient on-device pose detection.
    -   **MediaPipe Landmarker**: Supports **Lite**, **Full**, and **Heavy** models for varying degrees of accuracy and performance.
-   **VRChat Native Integration**: Maps directly to VRChat's `/tracking/trackers` indexed schema (Trackers 1-9).
-   **OSC Networking**: High-performance UDP streaming with **OSC Bundle** support to minimize network overhead.
-   **Neutral Pose Calibration**: One-tap calibration with a 5-second countdown to establish user-specific skeletal offsets.
-   **Advanced Signal Processing**:
    -   **Exponential Moving Average (EMA)** smoothing to eliminate jitter.
    -   **Dynamic Scaling**: Automatic height estimation and world-space mapping based on user input.
-   **Debug & Visualization**:
    -   Real-time skeleton overlay with joint axis visualization.
    -   **MJPEG Web Server**: Integrated server (port 8080) providing a remote MJPEG stream of the tracking view for monitoring on a PC.
-   **Camera Control**: 
    -   **Camera Selection**: Choose from all available device cameras (Front, Back, Wide, etc.).
    -   **Hardware Info**: Displays camera hardware support level (FULL, LEVEL_3, etc.) and real-time capture FPS.
    -   **Mirroring**: Optional horizontal mirroring for both preview and streaming.
-   **UI & Persistence**:
    -   **Immersive Mode**: Toggle UI visibility to focus on the camera feed.
    -   **Persistent Config**: Automatically saves IP, port, height, smoothing, camera choice, and joint offsets.
    -   **Reset Utility**: Quick reset to default settings.

## 📱 Hardware & Software Requirements

-   **Android Version**: API 26 (Android 8.0) or higher.
-   **Camera**: Recommended device with "FULL" or "LEVEL_3" hardware support for optimal frame rates (60 FPS+).
-   **Network**: Smartphone and VR headset/PC must be on the same local network.

## 🛠️ OSC Tracker Mapping

The following trackers are transmitted to the `/tracking/trackers/{id}/` path:

## 📡 Tracked OSC Endpoints

The application broadcasts data to 9 indexed VRChat-compliant OSC tracker addresses:

| Index | Tracker Name | Position OSC Address               | Rotation OSC Address               |
| :--- | :--- |:-----------------------------------|:-----------------------------------|
| **1** | **Hip** | `/tracking/trackers/1/position`    | `/tracking/trackers/1/rotation`    |
| **2** | **Chest** | `/tracking/trackers/2/position`    | `/tracking/trackers/2/rotation`    |
| **3** | **Left Foot** | `/tracking/trackers/3/position`    | `/tracking/trackers/3/rotation`    |
| **4** | **Right Foot** | `/tracking/trackers/4/position`    | `/tracking/trackers/4/rotation`    |
| **5** | **Left Knee** | `/tracking/trackers/5/position`    | `/tracking/trackers/5/rotation`    |
| **6** | **Right Knee** | `/tracking/trackers/6/position`    | `/tracking/trackers/6/rotation`    |
| **7** | **Left Elbow** | `/tracking/trackers/7/position`    | `/tracking/trackers/7/rotation`    |
| **8** | **Right Elbow** | `/tracking/trackers/8/position`    | `/tracking/trackers/8/rotation`    |
| **9** | **Head** | `/tracking/trackers/head/position` | `/tracking/trackers/head/rotation` |

## 📖 How to Use

1.  **Permissions**: Grant camera and internet permissions upon launch.
2.  **Configuration**:
    *   **IP Address**: Enter the local IP of your PC or Quest.
    *   **Port**: Default is `9000` for VRChat.
    *   **User Height**: Enter your height in meters for accurate tracker scaling.
    *   **Model Selection**: Choose between ML Kit or MediaPipe variants.
3.  **Joint Tuning**:
    *   Tap **Adjust Joints** to open the offset dialog.
    *   Fine-tune the Y-offset for vertical alignment or X-offset for limb width.
4.  **Calibration**:
    *   Tap **Calibrate Neutral Pose**.
    *   Stand still in a neutral pose (T-Pose or I-Pose) facing the camera during the countdown.
5.  **Streaming**:
    *   Tap **Start Stream**.
    *   Enable OSC in VRChat (Action Menu > Expressions > Options > OSC > Enabled).
6.  **Remote Monitoring**: Access `http://[PHONE_IP]:8080` in a PC browser to view the skeletal overlay. *Note: Monitoring may impact mobile processing performance.*

*Pro-tip: If you change the destination port to 9002, you can use the `tools/vrchat_bridge.ps1` script to inspect outgoing data before it reaches VRChat.*

## 🏗️ Project Structure

-   `MainActivity.kt`: Main UI controller and camera lifecycle management.
-   `PoseTracker.kt`: Bridge for ML Kit and MediaPipe inference engines.
-   `PoseOscMapper.kt`: Translates normalized 3D landmarks into VRChat-space OSC messages.
-   `JointAdjustmentsDialog.kt`: UI for real-time skeletal offset calibration.
-   `PoseProcessing.kt`: Implements EMA smoothing and calibration logic.
-   `OscSender.kt`: High-performance UDP transmission.
-   `AppConfigStore.kt`: SharedPreferences-based configuration persistence.
-   `JointOverlayView.kt`: Renders the skeleton and axes on the device screen.

## 📄 License

This project is intended for personal and development use.
If you really want to do something better with this, at least cite the original owner.
