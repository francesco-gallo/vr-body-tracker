import time
import threading
from collections import defaultdict
import matplotlib.pyplot as plt
from matplotlib.animation import FuncAnimation
from pythonosc.dispatcher import Dispatcher
from pythonosc.osc_server import BlockingOSCUDPServer

# Struttura per memorizzare coordinate e timestamp di ultimo aggiornamento
# points_3d["joint_name"] = {"pos": [x, y, z], "last_updated": timestamp}
points_3d = {}
total_packets_received = 0

# Tempo massimo in secondi prima di far scomparire un punto
TIMEOUT_SECONDS = 0.5

# --------------------------------------------------------------------------
# Connessioni dello scheletro
# --------------------------------------------------------------------------
SKELETON_BONES = [
    # Catena spinale
    ("hip", "chest"),
    ("chest", "head"),
    
    # Braccia
    ("chest", "left_elbow"),
    ("chest", "right_elbow"),
    
    # Gambe
    ("hip", "left_knee"),
    ("left_knee", "left_foot"),
    ("hip", "right_knee"),
    ("right_knee", "right_foot")
]

def handle_osc_data(address, *args):
    """
    Gestisce l'arrivo dei messaggi OSC ed esegue il timestamping del punto.
    """
    global points_3d, total_packets_received
    total_packets_received += 1
    current_time = time.time()

    clean_addr = address.strip('/').lower()
    parts = clean_addr.split('/')

    # Validazione struttura: /tracking/trackers/[joint]/[position]
    if len(parts) >= 4 and parts[0] == "tracking" and parts[1] == "trackers":
        joint_name = parts[2]
        datatype = parts[3]

        if joint_name not in points_3d:
            points_3d[joint_name] = {"pos": [0.0, 0.0, 0.0], "last_updated": current_time}

        # Pacchetto posizione completo (x, y, z)
        if datatype == "position" and len(args) >= 3:
            if all(isinstance(a, (int, float)) for a in args[:3]):
                points_3d[joint_name]["pos"] = [float(args[0]), float(args[1]), float(args[2])]
                points_3d[joint_name]["last_updated"] = current_time
                
        # Gestione sotto-assi singoli (es. /position/x)
        elif datatype == "position" and len(parts) == 5 and len(args) >= 1:
            axis = parts[4]
            val = float(args[0])
            if axis in ['x', '0']:
                points_3d[joint_name]["pos"][0] = val
            elif axis in ['y', '1']:
                points_3d[joint_name]["pos"][1] = val
            elif axis in ['z', '2']:
                points_3d[joint_name]["pos"][2] = val
            points_3d[joint_name]["last_updated"] = current_time

def start_osc_server(ip, port):
    """Esegue il server UDP in background."""
    dispatcher = Dispatcher()
    dispatcher.map("*", handle_osc_data)
    
    server = BlockingOSCUDPServer(("0.0.0.0", port), dispatcher)
    print(f"\n=============================================")
    print(f" UDP Server attivo su porta {port}")
    print(f" Timeout scomparsa punti: {TIMEOUT_SECONDS}s")
    print(f"=============================================\n")
    server.serve_forever()

def update_plot(frame, ax):
    """Aggiorna il grafico filtrando ed eliminando i punti scaduti."""
    ax.clear()
    current_time = time.time()
    
    # Stile grafico
    ax.set_facecolor('#121212')
    ax.xaxis.pane.fill = False
    ax.yaxis.pane.fill = False
    ax.zaxis.pane.fill = False
    ax.tick_params(colors='white')
    ax.set_xlabel('X', color='white')
    ax.set_ylabel('Y', color='white')
    ax.set_zlabel('Z', color='white')

    # ----------------------------------------------------------------------
    # 1. Filtra i punti attivi ricevuti negli ultimi 0.5 secondi
    # ----------------------------------------------------------------------
    active_points = {
        joint: data["pos"]
        for joint, data in list(points_3d.items())
        if (current_time - data["last_updated"]) <= TIMEOUT_SECONDS
    }

    ax.set_title(f'3D Centered Skeleton (Punti Attivi: {len(active_points)})', color='white')

    if not active_points:
        status_text = (
            f"In ascolto sulla porta {9002}...\n"
            f"Pacchetti Totali: {total_packets_received}\n\n"
            f"Nessun punto attivo (Timeout {TIMEOUT_SECONDS}s)"
        )
        ax.text2D(0.5, 0.5, status_text, color='#ffb74d', ha='center', va='center', transform=ax.transAxes)
        ax.set_xlim([-1, 1])
        ax.set_ylim([-1, 1])
        ax.set_zlim([-1, 1])
        return

    # ----------------------------------------------------------------------
    # 2. Calcola l'offset per centrare su Hip & Chest (se presenti)
    # ----------------------------------------------------------------------
    offset = [0.0, 0.0, 0.0]
    if "hip" in active_points and "chest" in active_points:
        hip = active_points["hip"]
        chest = active_points["chest"]
        offset = [(hip[0] + chest[0]) / 2.0, (hip[1] + chest[1]) / 2.0, (hip[2] + chest[2]) / 2.0]
    elif "hip" in active_points:
        offset = list(active_points["hip"])
    elif "chest" in active_points:
        offset = list(active_points["chest"])

    # Coordinate centrate dei soli punti attivi
    centered_points = {
        joint: [pos[0] - offset[0], pos[1] - offset[1], pos[2] - offset[2]]
        for joint, pos in active_points.items()
    }

    # ----------------------------------------------------------------------
    # 3. Disegna le linee dello scheletro
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
    # 4. Disegna nodi ed etichette dei punti attivi
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

    # Viewport fisso centrato sull'origine
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