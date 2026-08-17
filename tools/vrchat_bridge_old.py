#!/usr/bin/env python3
"""Bridge phone landmarks to VRChat OSC Trackers API.

Input expected from the Android app:
- /tracking/pose/<joint_name> [x, y, z, visibility]

Output forwarded to VRChat tracker endpoints:
- /tracking/trackers/<id>/position
- /tracking/trackers/<id>/rotation
- /tracking/trackers/head/position (optional)
- /tracking/trackers/head/rotation (optional)
"""

from __future__ import annotations

import argparse
import signal
import sys
from dataclasses import dataclass
from math import atan2, sqrt
from typing import Dict, Optional

from pythonosc.dispatcher import Dispatcher
from pythonosc.osc_server import BlockingOSCUDPServer
from pythonosc.udp_client import SimpleUDPClient


@dataclass
class BridgeConfig:
    listen_ip: str
    listen_port: int
    vrchat_ip: str
    vrchat_port: int
    in_prefix: str
    vis_min: float
    height_m: float
    send_head: bool


@dataclass
class JointState:
    x: float
    y: float
    z: float
    vis: float


@dataclass
class Vec3:
    x: float
    y: float
    z: float


class VrchatBridge:
    def __init__(self, config: BridgeConfig) -> None:
        self.config = config
        self.client = SimpleUDPClient(config.vrchat_ip, config.vrchat_port)
        self.joints: Dict[str, JointState] = {}

    def handle_pose(self, address: str, *args: object) -> None:
        if len(args) < 4:
            return

        try:
            x = float(args[0])
            y = float(args[1])
            z = float(args[2])
            vis = float(args[3])
        except (TypeError, ValueError):
            return

        joint = address.replace(self.config.in_prefix + "/", "", 1)
        self.joints[joint] = JointState(x=x, y=y, z=z, vis=vis)
        self.forward_trackers()

    def forward_trackers(self) -> None:
        hip = self.avg("left_hip", "right_hip")
        chest = self.avg("left_shoulder", "right_shoulder")
        left_foot = self.get("left_ankle")
        right_foot = self.get("right_ankle")
        left_knee = self.get("left_knee")
        right_knee = self.get("right_knee")
        left_elbow = self.get("left_elbow")
        right_elbow = self.get("right_elbow")
        head = self.get("nose")

        origin = hip or head
        if origin is None:
            return

        observed_height = self.estimate_height()
        meters_per_norm = self.config.height_m / max(0.2, observed_height)
        depth_scale = meters_per_norm * 0.25
        torso_yaw = self.estimate_torso_yaw_degrees()

        self.send_tracker(1, hip, self.vec3(0.0, torso_yaw, 0.0), origin, meters_per_norm, depth_scale)
        self.send_tracker(2, chest, self.vec3(0.0, torso_yaw, 0.0), origin, meters_per_norm, depth_scale)
        self.send_tracker(3, left_foot, self.rotation_from_direction(left_knee, left_foot, torso_yaw), origin, meters_per_norm, depth_scale)
        self.send_tracker(4, right_foot, self.rotation_from_direction(right_knee, right_foot, torso_yaw), origin, meters_per_norm, depth_scale)
        self.send_tracker(5, left_knee, self.rotation_from_direction(hip, left_knee, torso_yaw), origin, meters_per_norm, depth_scale)
        self.send_tracker(6, right_knee, self.rotation_from_direction(hip, right_knee, torso_yaw), origin, meters_per_norm, depth_scale)
        self.send_tracker(7, left_elbow, self.rotation_from_direction(self.get("left_shoulder"), left_elbow, torso_yaw), origin, meters_per_norm, depth_scale)
        self.send_tracker(8, right_elbow, self.rotation_from_direction(self.get("right_shoulder"), right_elbow, torso_yaw), origin, meters_per_norm, depth_scale)

        if self.config.send_head and head is not None:
            hp = self.to_tracking_space(head, origin, meters_per_norm, depth_scale)
            self.client.send_message("/tracking/trackers/head/position", [hp.x, hp.y, hp.z])
            self.client.send_message("/tracking/trackers/head/rotation", [0.0, torso_yaw, 0.0])

    def send_tracker(
        self,
        tracker_id: int,
        point: Optional[Vec3],
        rotation: Vec3,
        origin: Vec3,
        meters_per_norm: float,
        depth_scale: float,
    ) -> None:
        if point is None:
            return
        p = self.to_tracking_space(point, origin, meters_per_norm, depth_scale)
        self.client.send_message(f"/tracking/trackers/{tracker_id}/position", [p.x, p.y, p.z])
        self.client.send_message(f"/tracking/trackers/{tracker_id}/rotation", [rotation.x, rotation.y, rotation.z])

    def estimate_torso_yaw_degrees(self) -> float:
        shoulder = self.yaw_from_left_right(self.get("left_shoulder"), self.get("right_shoulder"))
        hip = self.yaw_from_left_right(self.get("left_hip"), self.get("right_hip"))
        if shoulder is not None and hip is not None:
            return (shoulder + hip) * 0.5
        if shoulder is not None:
            return shoulder
        if hip is not None:
            return hip
        return 0.0

    def yaw_from_left_right(self, left: Optional[Vec3], right: Optional[Vec3]) -> Optional[float]:
        if left is None or right is None:
            return None
        side_x = right.x - left.x
        side_z = right.z - left.z
        if abs(side_x) < 1e-4 and abs(side_z) < 1e-4:
            return None
        forward_x = -side_z
        forward_z = side_x
        return self.radians_to_degrees(atan2(forward_x, forward_z))

    def rotation_from_direction(self, start: Optional[Vec3], end: Optional[Vec3], default_yaw: float) -> Vec3:
        if start is None or end is None:
            return self.vec3(0.0, default_yaw, 0.0)
        dx = end.x - start.x
        dy = start.y - end.y
        dz = end.z - start.z
        yaw = self.radians_to_degrees(atan2(dx, dz))
        horizontal = max(1e-4, sqrt((dx * dx) + (dz * dz)))
        pitch = self.radians_to_degrees(atan2(dy, horizontal))
        return self.vec3(pitch, yaw, 0.0)

    def radians_to_degrees(self, value: float) -> float:
        return value * 57.29578

    def vec3(self, x: float, y: float, z: float) -> Vec3:
        return Vec3(x=x, y=y, z=z)

    def to_tracking_space(self, point: Vec3, origin: Vec3, meters_per_norm: float, depth_scale: float) -> Vec3:
        x = (point.x - origin.x) * meters_per_norm
        y = (origin.y - point.y) * meters_per_norm
        z = (point.z - origin.z) * depth_scale
        return Vec3(x=x, y=y, z=z)

    def get(self, name: str) -> Optional[Vec3]:
        data = self.joints.get(name)
        if data is None or data.vis < self.config.vis_min:
            return None
        return Vec3(x=data.x, y=data.y, z=data.z)

    def avg(self, a: str, b: str) -> Optional[Vec3]:
        left = self.get(a)
        right = self.get(b)
        if left is None and right is None:
            return None
        if left is None:
            return right
        if right is None:
            return left
        return Vec3(
            x=(left.x + right.x) * 0.5,
            y=(left.y + right.y) * 0.5,
            z=(left.z + right.z) * 0.5,
        )

    def estimate_height(self) -> float:
        y_values = []
        for name in ["nose", "left_shoulder", "right_shoulder", "left_ankle", "right_ankle"]:
            p = self.get(name)
            if p is not None:
                y_values.append(p.y)
        if len(y_values) < 2:
            return 1.0
        return abs(max(y_values) - min(y_values))


def parse_args() -> BridgeConfig:
    parser = argparse.ArgumentParser(description="Bridge phone pose OSC to VRChat OSC trackers")
    parser.add_argument("--listen-ip", default="0.0.0.0")
    parser.add_argument("--listen-port", type=int, default=9000)
    parser.add_argument("--vrchat-ip", default="127.0.0.1")
    parser.add_argument("--vrchat-port", type=int, default=9001)
    parser.add_argument("--in-prefix", default="/tracking/pose")
    parser.add_argument("--vis-min", type=float, default=0.5)
    parser.add_argument("--height-m", type=float, default=1.70)
    parser.add_argument("--send-head", action="store_true")

    ns = parser.parse_args()
    return BridgeConfig(
        listen_ip=ns.listen_ip,
        listen_port=ns.listen_port,
        vrchat_ip=ns.vrchat_ip,
        vrchat_port=ns.vrchat_port,
        in_prefix=ns.in_prefix.rstrip("/"),
        vis_min=ns.vis_min,
        height_m=max(1.0, min(2.5, ns.height_m)),
        send_head=ns.send_head,
    )


def main() -> int:
    config = parse_args()
    bridge = VrchatBridge(config)

    dispatcher = Dispatcher()
    dispatcher.map(f"{config.in_prefix}/*", bridge.handle_pose)

    server = BlockingOSCUDPServer((config.listen_ip, config.listen_port), dispatcher)

    def stop_server(_sig: int, _frame: object) -> None:
        server.server_close()
        raise SystemExit(0)

    signal.signal(signal.SIGINT, stop_server)
    signal.signal(signal.SIGTERM, stop_server)

    print(f"Listening on {config.listen_ip}:{config.listen_port}")
    print(f"Forwarding to {config.vrchat_ip}:{config.vrchat_port}")
    print("Press Ctrl+C to stop")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass

    return 0


if __name__ == "__main__":
    sys.exit(main())
