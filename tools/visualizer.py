import threading
from collections import defaultdict
import matplotlib.pyplot as plt
from matplotlib.animation import FuncAnimation
from pythonosc.dispatcher import Dispatcher
from pythonosc.osc_server import BlockingOSCUDPServer

# Dictionary storing raw 3D coordinates: points_3d["joint_name"] = [x, y, z]
points_3d = defaultdict(lambda: [0.0, 0.0, 0.0])
total_packets_received = 0

# --------------------------------------------------------------------------
# Skeleton Bone Connections
# --------------------------------------------------------------------------
SKELETON_BONES = [
    # Spine Chain
    ("hip", "chest"),
    ("chest", "head"),
    
    # Arms
    ("chest", "left_elbow"),
    ("chest", "right_elbow"),
    
    # Legs
    ("hip", "left_knee"),
    ("left_knee", "left_foot"),
    ("hip", "right_knee"),
    ("right_knee", "right_foot")
]

def handle_osc_data(address, *args):
    """
    Parses OSC addresses following the pattern:
    /tracking/trackers/[joint]/[rotation-position]
    """
    global points_3d, total_packets_received
    total_packets_received += 1

    clean_addr = address.strip('/').lower()
    parts = clean_addr.split('/')

    # Validate structure: must start with tracking/trackers/
    if len(parts) >= 4 and parts[0] == "tracking" and parts[1] == "trackers":
        joint_name = parts[2]
        datatype = parts[3]

        # Process position packets (expects 3 floats: x, y, z)
        if datatype == "position" and len(args) >= 3:
            if all(isinstance(a, (int, float)) for a in args[:3]):
                points_3d[joint_name] = [float(args[0]), float(args[1]), float(args[2])]
                
        # Sub-axis fallback (e.g. /tracking/trackers/head/position/x)
        elif datatype == "position" and len(parts) == 5 and len(args) >= 1:
            axis = parts[4]
            val = float(args[0])
            if axis in ['x', '0']:
                points_3d[joint_name][0] = val
            elif axis in ['y', '1']:
                points_3d[joint_name][1] = val
            elif axis in ['z', '2']:
                points_3d[joint_name][2] = val

def start_osc_server(ip, port):
    """Runs the UDP server in a background thread."""
    dispatcher = Dispatcher()
    dispatcher.map("*", handle_osc_data)
    
    server = BlockingOSCUDPServer(("0.0.0.0", port), dispatcher)
    print(f"\n=============================================")
    print(f" UDP Server active on 0.0.0.0:{port}")
    print(f" Pattern: /tracking/trackers/[joint]/[position]")
    print(f" Center Locked: Midpoint of Hip & Chest")
    print(f"=============================================\n")
    server.serve_forever()

def update_plot(frame, ax):
    """Refreshes the 3D Matplotlib canvas with centered coordinates."""
    ax.clear()
    
    # Dark UI layout
    ax.set_facecolor('#121212')
    ax.xaxis.pane.fill = False
    ax.yaxis.pane.fill = False
    ax.zaxis.pane.fill = False
    ax.tick_params(colors='white')
    ax.set_xlabel('X', color='white')
    ax.set_ylabel('Y', color='white')
    ax.set_zlabel('Z', color='white')
    ax.set_title(f'3D Centered Skeleton (Packets: {total_packets_received})', color='white')

    if not points_3d:
        status_text = (
            f"Listening on UDP Port 9002...\n"
            f"Total Packets Recv: {total_packets_received}\n\n"
            f"Waiting for position packets..."
        )
        ax.text2D(0.5, 0.5, status_text, color='#ffb74d', ha='center', va='center', transform=ax.transAxes)
        ax.set_xlim([-1, 1])
        ax.set_ylim([-1, 1])
        ax.set_zlim([-1, 1])
        return

    # ----------------------------------------------------------------------
    # 1. Calculate Center Offset (Midpoint of Hip & Chest)
    # ----------------------------------------------------------------------
    offset = [0.0, 0.0, 0.0]
    
    if "hip" in points_3d and "chest" in points_3d:
        hip = points_3d["hip"]
        chest = points_3d["chest"]
        offset = [
            (hip[0] + chest[0]) / 2.0,
            (hip[1] + chest[1]) / 2.0,
            (hip[2] + chest[2]) / 2.0
        ]
    elif "hip" in points_3d:
        offset = list(points_3d["hip"])
    elif "chest" in points_3d:
        offset = list(points_3d["chest"])

    # Compute centered coordinates for all available points
    centered_points = {}
    for joint, coord in list(points_3d.items()):
        centered_points[joint] = [
            coord[0] - offset[0],
            coord[1] - offset[1],
            coord[2] - offset[2]
        ]

    # ----------------------------------------------------------------------
    # 2. Draw Skeleton Lines
    # ----------------------------------------------------------------------
    for p1_key, p2_key in SKELETON_BONES:
        if p1_key in centered_points and p2_key in centered_points:
            pt1 = centered_points[p1_key]
            pt2 = centered_points[p2_key]
            ax.plot([pt1[0], pt2[0]], 
                    [pt1[1], pt2[1]], 
                    [pt1[2], pt2[2]], 
                    color='#ff1744', linewidth=3.0, alpha=0.85)

    # ----------------------------------------------------------------------
    # 3. Draw Joint Points & Labels
    # ----------------------------------------------------------------------
    xs, ys, zs, labels = [], [], [], []
    for joint, coord in centered_points.items():
        xs.append(coord[0])
        ys.append(coord[1])
        zs.append(coord[2])
        labels.append(joint)

    ax.scatter(xs, ys, zs, c='#00e5ff', s=60, edgecolors='white', zorder=5)
    for x, y, z, lbl in zip(xs, ys, zs, labels):
        ax.text(x, y, z, f" {lbl}", color='#00e5ff', fontsize=9, fontweight='bold')

    # Fixed viewport boundary around origin (1.5m radius from center)
    view_range = 1.2
    ax.set_xlim([-view_range, view_range])
    ax.set_ylim([-view_range, view_range])
    ax.set_zlim([-view_range, view_range])

def main():
    port = 9002

    # Run UDP server in background thread
    osc_thread = threading.Thread(target=start_osc_server, args=("", port), daemon=True)
    osc_thread.start()

    # Matplotlib 3D setup
    fig = plt.figure(figsize=(10, 8))
    fig.patch.set_facecolor('#121212')
    ax = fig.add_subplot(111, projection='3d')

    anim = FuncAnimation(fig, update_plot, fargs=(ax,), interval=50, cache_frame_data=False)
    plt.show()

if __name__ == "__main__":
    main()