# VR Body OSC (Android)

Android app that:

- Captures body landmarks from the phone camera using ML Kit Pose Detection.
- Streams VRChat OSC Trackers API messages over UDP by default.
- Lets you configure destination IP, port, output mode, and head alignment in-app.
- Adds runtime controls for camera selection, FPS cap, smoothing, calibration, axis inversion, and OSC bundle mode.

## What this sends

Default mode is VRChat trackers API:

- `/tracking/trackers/1/position` + `/tracking/trackers/1/rotation` (hip)
- `/tracking/trackers/2/position` + `/tracking/trackers/2/rotation` (chest)
- `/tracking/trackers/3/position` + `/tracking/trackers/3/rotation` (left foot)
- `/tracking/trackers/4/position` + `/tracking/trackers/4/rotation` (right foot)
- `/tracking/trackers/5/position` + `/tracking/trackers/5/rotation` (left knee)
- `/tracking/trackers/6/position` + `/tracking/trackers/6/rotation` (right knee)
- `/tracking/trackers/7/position` + `/tracking/trackers/7/rotation` (left elbow)
- `/tracking/trackers/8/position` + `/tracking/trackers/8/rotation` (right elbow)

Optional head alignment:

- `/tracking/trackers/head/position`
- `/tracking/trackers/head/rotation`

Each message carries a Vector3: 3 floats (X, Y, Z).

Raw landmarks mode is also available:

For each detected joint, the app sends one OSC message:

- Address: `/tracking/pose/<joint_name>` (or your custom prefix)
- Arguments: `x`, `y`, `z`, `visibility` (all floats)

It also sends:

- Address: `/tracking/pose/frame_time_ms`
- Argument: timestamp in milliseconds as an int

When bundle mode is enabled, these are packed into one OSC bundle per frame.

Coordinates:

- `x` and `y` are normalized to `[0, 1]` from camera frame size.
- `z` comes from ML Kit `position3D.z`.

## Open and run

1. Open this folder in Android Studio.
2. Let Gradle sync.
3. If you need command-line wrapper files, install Gradle CLI and run:
   - `gradle wrapper`
4. Run on a real Android device (camera permission is required).

## VRChat notes

VRChat full-body over OSC expects tracker endpoints under `/tracking/trackers/...`.
This app now targets those addresses directly by default.

Typical integration pipeline:

1. Android app sends tracker OSC to your PC where VRChat runs.
2. VRChat consumes tracker messages on port 9001.

If you need custom filtering/remapping, use the included bridge.

You can set this app's prefix to match what your bridge expects.

## Desktop bridge (included)

Included utility:

- `tools/vrchat_bridge.py`

Install and run on PC:

1. `cd tools`
2. `python -m venv .venv`
3. `.venv\Scripts\activate`
4. `pip install -r requirements.txt`
5. `python vrchat_bridge.py --listen-port 9000 --vrchat-port 9001`

The bridge listens for:

- `/tracking/pose/<joint>` with args `[x, y, z, visibility]`

And forwards mapped trackers to official VRChat tracker paths:

- `/tracking/trackers/1..8/position`
- `/tracking/trackers/1..8/rotation`
- Optional `/tracking/trackers/head/position` and `/tracking/trackers/head/rotation`

Example:

- `python vrchat_bridge.py --listen-port 9000 --vrchat-port 9001 --height-m 1.70 --send-head`

## Default endpoint in UI

- IP: `192.168.1.10`
- Port: `9000`
- Prefix: `/tracking/pose`

Tracking defaults:

- Back camera by default (best quality path).
- VRChat trackers mode enabled by default.
- Head alignment sending enabled by default.
- OSC bundle enabled.
- FPS cap set to 20.
- Smoothing set to 35%.

Change these to your PC local IP and your bridge listener port.
