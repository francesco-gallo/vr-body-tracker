import time
import threading
from collections import defaultdict
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
Z_BOUND_MARGIN = 1.0  # Limite +/- 1.0m dalla Z di base

def handle_osc_data(address, *args):
    """Parsa i percorsi /tracking/trackers/[ID]/position e memorizza le coordinate (x, y, z)."""
    global points_3d, total_packets_received
    total_packets_received += 1
    current_time = time.time()

    clean_addr = address.strip('/').lower()
    parts = clean_addr.split('/')

    # Verifica il pattern: /tracking/trackers/[raw_id]/position
    if len(parts) >= 4 and parts[0] == "tracking" and parts[1] == "trackers":
        raw_id = parts[2]
        datatype = parts[3]

        joint_name = TRACKER_MAP.get(raw_id, raw_id)

        if datatype == "position" and len(args) >= 3:
            if all(isinstance(a, (int, float)) for a in args[:3]):
                points_3d[joint_name] = {
                    "pos": [float(args[0]), float(args[1]), float(args[2])],
                    "last_updated": current_time
                }
                
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
    print(f" Centro X/Y: Midpoint (Chest + Hip)")
    print(f" Base Z: Head -> Chest -> Hip (Margine: +/-1.0m)")
    print(f"=============================================\n")
    server.serve_forever()

def update_plot(frame, ax):
    """Disegna e aggiorna la scena 3D applicando il posizionamento specificato."""
    ax.clear()
    current_time = time.time()
    
    # Stile finestra 3D
    ax.set_facecolor('#121212')
    ax.xaxis.pane.fill = False
    ax.yaxis.pane.fill = False
    ax.zaxis.pane.fill = False
    ax.tick_params(colors='white')
    ax.set_xlabel('X (Sinistra/Destra)', color='white')
    ax.set_ylabel('Z (Profondità)', color='white')
    ax.set_zlabel('Y (Verticale)', color='white')

    # 1. Filtro temporale (punti ricevuti negli ultimi 0.5s)
    time_active_points = {
        joint: data["pos"]
        for joint, data in list(points_3d.items())
        if (current_time - data["last_updated"]) <= TIMEOUT_SECONDS
    }

    if not time_active_points:
        status_text = (
            f"In ascolto sulla porta {9002}...\n"
            f"Pacchetti Totali: {total_packets_received}\n\n"
            f"In attesa di dati per lo scheletro..."
        )
        ax.text2D(0.5, 0.5, status_text, color='#ffb74d', ha='center', va='center', transform=ax.transAxes)
        ax.set_xlim([-1, 1])
        ax.set_ylim([-1, 1])
        ax.set_zlim([-1, 1])
        return

    # ----------------------------------------------------------------------
    # 2. Calcolo del Centro X/Y (Midpoint tra Chest e Hip)
    # ----------------------------------------------------------------------
    center_x, center_y = 0.0, 0.0
    if "chest" in time_active_points and "hip" in time_active_points:
        center_x = (time_active_points["chest"][0] + time_active_points["hip"][0]) / 2.0
        center_y = (time_active_points["chest"][1] + time_active_points["hip"][1]) / 2.0
    elif "chest" in time_active_points:
        center_x = time_active_points["chest"][0]
        center_y = time_active_points["chest"][1]
    elif "hip" in time_active_points:
        center_x = time_active_points["hip"][0]
        center_y = time_active_points["hip"][1]

    # ----------------------------------------------------------------------
    # 3. Determinazione della Base Z di riferimento (Head -> Chest -> Hip)
    # ----------------------------------------------------------------------
    priority_order = ["head", "chest", "hip"]
    base_z = None
    ref_z_joint = None

    for candidate in priority_order:
        if candidate in time_active_points:
            base_z = time_active_points[candidate][2]
            ref_z_joint = candidate
            break

    # Se nessuno dei tre è disponibile, usiamo la Z del primo punto presente
    if base_z is None:
        first_available = list(time_active_points.keys())[0]
        base_z = time_active_points[first_available][2]
        ref_z_joint = first_available

    # ----------------------------------------------------------------------
    # 4. Filtro Out of Bounds e Traslazione Coordinate per Matplotlib
    # ----------------------------------------------------------------------
    # Mappatura assi: X = OSC_X - center_x | Y = OSC_Z - base_z | Z = OSC_Y - center_y
    mapped_points = {}
    for joint, pos in time_active_points.items():
        z_dist = abs(pos[2] - base_z)
        
        if z_dist <= Z_BOUND_MARGIN:
            dx = pos[0] - center_x
            dy = pos[1] - center_y  # Y originaria (Altezza)
            dz = pos[2] - base_z     # Z originaria (Profondità rispetto alla base Z)
            mapped_points[joint] = (dx, dz, dy)

    ax.set_title(f'3D Skeleton (Center X/Y: Midpoint | Base Z: {ref_z_joint.upper()})', color='white')

    # 5. Disegno segmenti delle ossa
    for p1_key, p2_key in SKELETON_BONES:
        if p1_key in mapped_points and p2_key in mapped_points:
            pt1 = mapped_points[p1_key]
            pt2 = mapped_points[p2_key]
            ax.plot([pt1[0], pt2[0]], 
                    [pt1[1], pt2[1]], 
                    [pt1[2], pt2[2]], 
                    color='#ff1744', linewidth=3.0, alpha=0.85)

    # 6. Render dei nodi e delle etichette
    xs, ys, zs, labels = [], [], [], []
    for joint, (mx, my, mz) in mapped_points.items():
        xs.append(mx)
        ys.append(my)
        zs.append(mz)
        labels.append(joint)

    ax.scatter(xs, ys, zs, c='#00e5ff', s=60, edgecolors='white', zorder=5)
    for x, y, z, lbl in zip(xs, ys, zs, labels):
        tag = f" {lbl} (Z-BASE)" if lbl == ref_z_joint else f" {lbl}"
        ax.text(x, y, z, tag, color='#ffb74d' if lbl == ref_z_joint else '#00e5ff', fontsize=9, fontweight='bold')

    # Viewport fisso centrato
    ax.set_xlim([-1.2, 1.2])
    ax.set_ylim([-Z_BOUND_MARGIN, Z_BOUND_MARGIN])  # Asse Z della vista (Profondità)
    ax.set_zlim([-1.2, 1.2])

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