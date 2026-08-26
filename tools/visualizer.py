import time
import threading
from collections import defaultdict
import matplotlib
matplotlib.use('QtAgg')  # Uses Qt for OS window management instead of Tkinter
import matplotlib.pyplot as plt
from matplotlib.animation import FuncAnimation
from pythonosc.dispatcher import Dispatcher
from pythonosc.osc_server import BlockingOSCUDPServer

# Mappatura dagli ID numerici ai nomi estesi dello scheletro
TRACKER_MAP = {
    "1": "hip",
    "2": "chest",
    "3": "left_foot",
    "4": "right_foot",
    "5": "left_knee",
    "6": "right_knee",
    "7": "left_elbow",
    "8": "right_elbow",
    "head": "head"
}

# Connessioni tra i punti estesi
SKELETON_BONES = [
    # Spina dorsale
    ("hip", "chest"),
    ("chest", "head"),
    
    # Braccia
    ("chest", "left_elbow"),
    ("chest", "right_elbow"),
    
    # Gambe (Hip -> Knee -> Foot)
    ("hip", "left_knee"),
    ("left_knee", "left_foot"),
    ("hip", "right_knee"),
    ("right_knee", "right_foot")
]

# Modello dati: points_3d["joint_name"] = {"pos": [x, y, z], "last_updated": timestamp}
points_3d = {}
total_packets_received = 0
TIMEOUT_SECONDS = 0.5

def handle_osc_data(address, *args):
    """Parsa i percorsi /tracking/trackers/[ID]/position e li mappa ai nomi estesi."""
    global points_3d, total_packets_received
    total_packets_received += 1
    current_time = time.time()

    clean_addr = address.strip('/').lower()
    parts = clean_addr.split('/')

    # Verifica il pattern: /tracking/trackers/[raw_id]/position
    if len(parts) >= 4 and parts[0] == "tracking" and parts[1] == "trackers":
        raw_id = parts[2]
        datatype = parts[3]

        # Converti l'ID numerico nel nome esteso (se presente nella mappa)
        joint_name = TRACKER_MAP.get(raw_id, raw_id)

        # Gestisci solo i pacchetti di posizione
        if datatype == "position" and len(args) >= 3:
            if all(isinstance(a, (int, float)) for a in args[:3]):
                points_3d[joint_name] = {
                    "pos": [float(args[0]), float(args[1]), float(args[2])],
                    "last_updated": current_time
                }

        # Supporto per sotto-assi singoli (es. /position/x)
        elif datatype == "position" and len(parts) == 5 and len(args) >= 1:
            axis = parts[4]
            val = float(args[0])
            if joint_name not in points_3d:
                points_3d[joint_name] = {"pos": [0.0, 0.0, 0.0], "last_updated": current_time}

            if axis in ['x', '0']:
                points_3d[joint_name]["pos"][0] = val
            elif axis in ['y', '1']:
                points_3d[joint_name]["pos"][1] = val
            elif axis in ['z', '2']:
                points_3d[joint_name]["pos"][2] = val
            points_3d[joint_name]["last_updated"] = current_time

def start_osc_server(ip, port):
    """Avvia il server UDP su un thread dedicato."""
    dispatcher = Dispatcher()
    dispatcher.map("*", handle_osc_data)

    server = BlockingOSCUDPServer(("0.0.0.0", port), dispatcher)
    print(f"\n=============================================")
    print(f" Server UDP attivo su porta {port}")
    print(f" Mappatura ID 1..8 -> Nomi estesi applicata")
    print(f" Timeout di scomparsa punti: {TIMEOUT_SECONDS}s")
    print(f"=============================================\n")
    server.serve_forever()

def update_plot(frame, ax):
    """Disegna e aggiorna la scena 3D."""
    ax.clear()
    current_time = time.time()

    # Stile finestra 3D
    ax.set_facecolor('#121212')
    ax.xaxis.pane.fill = False
    ax.yaxis.pane.fill = False
    ax.zaxis.pane.fill = False
    ax.tick_params(colors='white')
    ax.set_xlabel('X', color='white')
    ax.set_ylabel('Y', color='white')
    ax.set_zlabel('Z', color='white')

    # 1. Filtro punti attivi (ricevuti negli ultimi 0.5s)
    active_points = {
        joint: data["pos"]
        for joint, data in list(points_3d.items())
        if (current_time - data["last_updated"]) <= TIMEOUT_SECONDS
    }

    ax.set_title(f'3D Skeleton (Punti attivi: {len(active_points)})', color='white')

    if not active_points:
        status_text = (
            f"In ascolto sulla porta {9002}...\n"
            f"Pacchetti Totali: {total_packets_received}\n\n"
            f"In attesa di dati per gli ID 1..8 o head..."
        )
        ax.text2D(0.5, 0.5, status_text, color='#ffb74d', ha='center', va='center', transform=ax.transAxes)
        ax.set_xlim([-1, 1])
        ax.set_ylim([-1, 1])
        ax.set_zlim([-1, 1])
        return

    # 2. Calcolo offset di centratura (Midpoint tra hip e chest)
    offset = [0.0, 0.0, 0.0]
    if "hip" in active_points and "chest" in active_points:
        hip = active_points["hip"]
        chest = active_points["chest"]
        offset = [(hip[0] + chest[0]) / 2.0, (hip[1] + chest[1]) / 2.0, (hip[2] + chest[2]) / 2.0]
    elif "hip" in active_points:
        offset = list(active_points["hip"])
    elif "chest" in active_points:
        offset = list(active_points["chest"])

    # Coordinate traslate rispetto al centro
    centered_points = {
        joint: [pos[0] - offset[0], pos[1] - offset[1], pos[2] - offset[2]]
        for joint, pos in active_points.items()
    }

    # 3. Disegno segmenti delle ossa
    for p1_key, p2_key in SKELETON_BONES:
        if p1_key in centered_points and p2_key in centered_points:
            pt1 = centered_points[p1_key]
            pt2 = centered_points[p2_key]
            ax.plot([pt1[0], pt2[0]],
                    [pt1[1], pt2[1]],
                    [pt1[2], pt2[2]],
                    color='#ff1744', linewidth=3.0, alpha=0.85)

    # 4. Render dei punti e dei nomi estesi a schermo
    xs, ys, zs, labels = [], [], [], []
    for joint, coord in centered_points.items():
        xs.append(coord[0])
        ys.append(coord[1])
        zs.append(coord[2])
        labels.append(joint)

    ax.scatter(xs, ys, zs, c='#00e5ff', s=60, edgecolors='white', zorder=5)
    for x, y, z, lbl in zip(xs, ys, zs, labels):
        ax.text(x, y, z, f" {lbl}", color='#00e5ff', fontsize=9, fontweight='bold')

    # Viewport fisso e centrato
    view_range = 1.2
    ax.set_xlim([-view_range, view_range])
    ax.set_ylim([-view_range, view_range])
    ax.set_zlim([-view_range, view_range])

def main():
    port = 9002

    osc_thread = threading.Thread(target=start_osc_server, args=("", port), daemon=True)
    osc_thread.start()

    fig = plt.figure(figsize=(10, 8))
    fig.patch.set_facecolor('#121212')
    ax = fig.add_subplot(111, projection='3d')

    anim = FuncAnimation(fig, update_plot, fargs=(ax,), interval=50, cache_frame_data=False)
    plt.show()

if __name__ == "__main__":
    main()