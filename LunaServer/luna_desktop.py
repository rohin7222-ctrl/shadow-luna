import ctypes
import json
import os
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.request
from collections import deque
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import webview

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PYTHON_EXE = os.path.join(BASE_DIR, ".venv", "Scripts", "python.exe")
if not os.path.exists(PYTHON_EXE):
    PYTHON_EXE = sys.executable

DB_PATH = os.path.join(BASE_DIR, "luna_memory.db")
LOG_PATH = os.path.join(BASE_DIR, "server.log")
RUNTIME_STATE_PATH = os.path.join(BASE_DIR, "runtime_state.json")
ICO_PATH = os.path.join(BASE_DIR, "shadow_luna.ico")
PNG_LOGO_PATH = os.path.join(os.path.dirname(BASE_DIR), "shadow_luna_logo.png")
STUDIO_HTML_PATH = os.path.join(BASE_DIR, "studio_ui.html")

SERVER_PORT = 8000
CONTROL_PORT = 8765


def get_wifi_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def is_port_open(host: str, port: int, timeout: float = 0.35) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except Exception:
        return False


def focus_existing_instance() -> bool:
    if not is_port_open("127.0.0.1", CONTROL_PORT, timeout=0.35):
        return False
    try:
        req = urllib.request.Request(f"http://127.0.0.1:{CONTROL_PORT}/ctl/focus", method="POST", data=b"{}")
        with urllib.request.urlopen(req, timeout=1.5) as resp:
            return resp.status == 200
    except Exception:
        return False


class LunaServerController:
    """Silent background Uvicorn supervisor + DB reader + AI CLI bridge."""

    def __init__(self):
        self.proc = None
        self.lock = threading.Lock()
        self.log_buffer = deque(maxlen=400)
        self.wifi_ip = get_wifi_ip()
        self.window = None

    def append_log(self, line: str):
        ts = datetime.now().strftime("%H:%M:%S")
        formatted = f"[{ts}] {line.rstrip()}"
        self.log_buffer.append(formatted)
        try:
            with open(LOG_PATH, "a", encoding="utf-8", errors="replace") as f:
                f.write(formatted + "\n")
        except Exception:
            pass

    def is_running(self) -> bool:
        with self.lock:
            managed_alive = self.proc is not None and self.proc.poll() is None
        return managed_alive or is_port_open("127.0.0.1", SERVER_PORT, timeout=0.25)

    def start_server(self) -> tuple[bool, str]:
        with self.lock:
            if self.proc is not None and self.proc.poll() is None:
                return True, "Server already running"

            if is_port_open("127.0.0.1", SERVER_PORT, timeout=0.3):
                self.append_log("Attached to active port 8000 listener.")
                self.write_runtime_state(True)
                return True, "Attached to running server on port 8000"

            creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)
            try:
                self.proc = subprocess.Popen(
                    [
                        PYTHON_EXE,
                        "-u",
                        "-m",
                        "uvicorn",
                        "luna_server:app",
                        "--host",
                        "0.0.0.0",
                        "--port",
                        str(SERVER_PORT),
                        "--reload",
                    ],
                    cwd=BASE_DIR,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    creationflags=creationflags,
                    bufsize=1,
                )
                self.append_log(f"Started Luna Server (PID {self.proc.pid}) on http://{self.wifi_ip}:{SERVER_PORT}")
                threading.Thread(target=self._read_process_output, args=(self.proc,), daemon=True).start()
                self.write_runtime_state(True)
                return True, f"Started on port {SERVER_PORT}"
            except Exception as e:
                self.append_log(f"ERROR starting server: {e}")
                self.write_runtime_state(False)
                return False, str(e)

    def _read_process_output(self, proc):
        try:
            for line in iter(proc.stdout.readline, ""):
                if not line:
                    break
                self.append_log(line)
        except Exception:
            pass
        finally:
            self.write_runtime_state(self.is_running())

    def stop_server(self) -> tuple[bool, str]:
        with self.lock:
            pid = self.proc.pid if (self.proc is not None and self.proc.poll() is None) else None
            self.proc = None

        no_window = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)
        killed = False
        if pid is not None:
            try:
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True, creationflags=no_window)
                killed = True
            except Exception:
                pass

        if is_port_open("127.0.0.1", SERVER_PORT, timeout=0.25):
            try:
                ns = subprocess.run(
                    ["netstat", "-ano", "-p", "TCP"],
                    capture_output=True,
                    text=True,
                    creationflags=no_window,
                )
                pids = set()
                for line in ns.stdout.splitlines():
                    parts = line.split()
                    if len(parts) >= 5 and f":{SERVER_PORT}" in parts[1] and parts[3].upper() == "LISTENING":
                        if parts[4].isdigit() and int(parts[4]) > 0:
                            pids.add(parts[4])
                for ext_pid in pids:
                    subprocess.run(["taskkill", "/F", "/T", "/PID", ext_pid], capture_output=True, creationflags=no_window)

                if is_port_open("127.0.0.1", SERVER_PORT, timeout=0.25) and pids:
                    pid_filter = " -or ".join([f"$_.ParentProcessId -eq {p} -or $_.ProcessId -eq {p}" for p in pids])
                    ps_cmd = (
                        f"Get-CimInstance Win32_Process -Filter \"Name LIKE 'python%'\" | "
                        f"Where-Object {{ {pid_filter} -or $_.CommandLine -like '*luna_server*' }} | "
                        f"ForEach-Object {{ Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }}"
                    )
                    subprocess.run(["powershell", "-NoProfile", "-Command", ps_cmd], capture_output=True, creationflags=no_window)
                killed = True
            except Exception:
                pass

        self.append_log("Server stopped." if killed else "Server is offline.")
        self.write_runtime_state(False)
        return True, "Server stopped"

    def restart_server(self) -> tuple[bool, str]:
        self.stop_server()
        time.sleep(0.45)
        return self.start_server()

    def get_db_snapshot(self) -> dict:
        if not os.path.exists(DB_PATH):
            return {
                "memories_count": 0, "nodes_count": 0, "tokens_count": 0, "db_size_kb": 0.0,
                "latest_version": "2.2.0", "memories": [], "nodes": [], "tokens": [], "sessions": [], "updates": []
            }
        try:
            conn = sqlite3.connect(DB_PATH)
            conn.row_factory = sqlite3.Row
            c = conn.cursor()
            mem_count = c.execute("SELECT COUNT(*) FROM memories").fetchone()[0]
            node_count = c.execute("SELECT COUNT(*) FROM graph_nodes").fetchone()[0]
            tok_count = c.execute("SELECT COUNT(*) FROM learned_tokens").fetchone()[0]
            updates = [dict(r) for r in c.execute("SELECT * FROM app_updates ORDER BY released_at DESC LIMIT 20").fetchall()]
            memories = [dict(r) for r in c.execute("SELECT * FROM memories ORDER BY timestamp DESC LIMIT 40").fetchall()]
            nodes = [dict(r) for r in c.execute("SELECT * FROM graph_nodes ORDER BY mention_count DESC LIMIT 30").fetchall()]
            tokens = []
            sessions = []
            try:
                tokens = [dict(r) for r in c.execute("SELECT * FROM learned_tokens ORDER BY frequency DESC, last_seen DESC LIMIT 150").fetchall()]
            except Exception:
                pass
            try:
                sessions = [dict(r) for r in c.execute("SELECT * FROM sync_sessions ORDER BY synced_at DESC LIMIT 10").fetchall()]
            except Exception:
                pass
            db_bytes = os.path.getsize(DB_PATH)
            conn.close()
            latest_ver = updates[0]["version"] if updates else "2.2.0"
            return {
                "memories_count": mem_count,
                "nodes_count": node_count,
                "tokens_count": tok_count,
                "db_size_kb": round(db_bytes / 1024.0, 1),
                "latest_version": latest_ver,
                "memories": memories,
                "nodes": nodes,
                "tokens": tokens,
                "sessions": sessions,
                "updates": updates,
            }
        except Exception as e:
            return {
                "memories_count": 0, "nodes_count": 0, "tokens_count": 0, "db_size_kb": 0.0,
                "latest_version": "?", "memories": [], "nodes": [], "tokens": [], "sessions": [], "updates": [], "error": str(e)
            }

    def publish_update(self, title: str, changelog: str, estimated_seconds: int = 12) -> dict:
        title = (title or "").strip() or "Server Code Update"
        changelog = (changelog or "").strip() or "Updated UI & Cognitive Engine"
        if self.is_running():
            try:
                body = json.dumps({"title": title, "changelog": changelog, "estimated_seconds": estimated_seconds}).encode("utf-8")
                req = urllib.request.Request(
                    f"http://127.0.0.1:{SERVER_PORT}/api/updates/publish",
                    method="POST",
                    data=body,
                    headers={"Content-Type": "application/json"},
                )
                with urllib.request.urlopen(req, timeout=4) as resp:
                    return json.loads(resp.read().decode("utf-8"))
            except Exception:
                pass

        conn = sqlite3.connect(DB_PATH)
        conn.row_factory = sqlite3.Row
        c = conn.cursor()
        rows = c.execute("SELECT version FROM app_updates ORDER BY released_at DESC").fetchall()
        latest = rows[0]["version"] if rows else "2.2.0"
        parts = [int(x) if x.isdigit() else 0 for x in latest.split(".")]
        while len(parts) < 3:
            parts.append(0)
        next_ver = f"{parts[0]}.{parts[1]}.{parts[2] + 1}"
        now_ms = int(time.time() * 1000)
        c.execute(
            "INSERT INTO app_updates (version, title, changelog, released_at, estimated_seconds, code_hash) VALUES (?, ?, ?, ?, ?, ?)",
            (next_ver, title, changelog, now_ms, max(5, estimated_seconds), f"manual-{now_ms}"),
        )
        conn.commit()
        conn.close()
        return {"status": "PUBLISHED", "version": next_ver, "released_at": now_ms}

    def reset_brain(self):
        if self.is_running():
            try:
                req = urllib.request.Request(f"http://127.0.0.1:{SERVER_PORT}/api/reset-brain", method="POST", data=b"{}")
                with urllib.request.urlopen(req, timeout=3):
                    pass
            except Exception:
                pass
        if os.path.exists(DB_PATH):
            conn = sqlite3.connect(DB_PATH)
            for tbl in ("memories", "graph_nodes", "graph_edges", "user_state", "learned_tokens", "sync_sessions"):
                try:
                    conn.execute(f"DELETE FROM {tbl}")
                except Exception:
                    pass
            conn.commit()
            conn.close()
        self.append_log("Luna AI Brain reset to 0 memories.")

    def status_dict(self) -> dict:
        snap = self.get_db_snapshot()
        running = self.is_running()
        self.wifi_ip = get_wifi_ip()
        return {
            "gui_running": True,
            "gui_pid": os.getpid(),
            "control_port": CONTROL_PORT,
            "server_running": running,
            "server_pid": self.proc.pid if (self.proc and self.proc.poll() is None) else None,
            "server_port": SERVER_PORT,
            "wifi_ip": self.wifi_ip,
            "local_url": f"http://127.0.0.1:{SERVER_PORT}",
            "lan_url": f"http://{self.wifi_ip}:{SERVER_PORT}",
            "latest_version": snap["latest_version"],
            "memories_count": snap["memories_count"],
            "nodes_count": snap["nodes_count"],
            "tokens_count": snap["tokens_count"],
            "recent_logs": list(self.log_buffer)[-40:],
        }

    def write_runtime_state(self, running: bool):
        try:
            state = {
                "gui_pid": os.getpid(),
                "control_url": f"http://127.0.0.1:{CONTROL_PORT}",
                "server_running": running,
                "server_port": SERVER_PORT,
                "lan_url": f"http://{self.wifi_ip}:{SERVER_PORT}",
                "updated_at": datetime.now().isoformat(),
            }
            with open(RUNTIME_STATE_PATH, "w", encoding="utf-8") as f:
                json.dump(state, f, indent=2)
        except Exception:
            pass

    def focus_window(self):
        try:
            hwnd = ctypes.windll.user32.FindWindowW(None, "Shadow")
            if hwnd:
                ctypes.windll.user32.ShowWindow(hwnd, 9)  # SW_RESTORE
                ctypes.windll.user32.SetForegroundWindow(hwnd)
        except Exception:
            pass


def start_control_http_server(ctrl: LunaServerController):
    class ControlHandler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            return

        def _send_bytes(self, code: int, content_type: str, data: bytes):
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _send_json(self, code: int, payload: dict):
            self._send_bytes(code, "application/json; charset=utf-8", json.dumps(payload, ensure_ascii=False).encode("utf-8"))

        def do_GET(self):
            if self.path == "/studio" or self.path == "/":
                try:
                    with open(STUDIO_HTML_PATH, "rb") as f:
                        self._send_bytes(200, "text/html; charset=utf-8", f.read())
                except Exception as e:
                    self._send_json(500, {"error": str(e)})
            elif self.path == "/studio/logo.png":
                try:
                    with open(PNG_LOGO_PATH, "rb") as f:
                        self._send_bytes(200, "image/png", f.read())
                except Exception:
                    self._send_json(404, {"error": "Logo missing"})
            elif self.path == "/ctl/studio-data":
                running = ctrl.is_running()
                snap = ctrl.get_db_snapshot()
                hs = {"phase": "IDLE"}
                if running:
                    try:
                        with urllib.request.urlopen(f"http://127.0.0.1:{SERVER_PORT}/api/handshake/status", timeout=0.6) as resp:
                            hs = json.loads(resp.read().decode("utf-8"))
                    except Exception:
                        pass
                self._send_json(
                    200,
                    {
                        "server_running": running,
                        "lan_url": f"http://{ctrl.wifi_ip}:{SERVER_PORT}",
                        "snapshot": snap,
                        "handshake": hs,
                    },
                )
            elif self.path == "/ctl/status":
                self._send_json(200, ctrl.status_dict())
            elif self.path == "/ctl/logs":
                self._send_json(200, {"logs": list(ctrl.log_buffer)})
            else:
                self._send_json(404, {"error": "Unknown endpoint"})

        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0") or 0)
            raw_body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
            try:
                body = json.loads(raw_body) if raw_body.strip() else {}
            except Exception:
                body = {}

            if self.path == "/ctl/start":
                ok, msg = ctrl.start_server()
                self._send_json(200, {"ok": ok, "message": msg, **ctrl.status_dict()})
            elif self.path == "/ctl/stop":
                ok, msg = ctrl.stop_server()
                self._send_json(200, {"ok": ok, "message": msg, **ctrl.status_dict()})
            elif self.path == "/ctl/restart":
                ok, msg = ctrl.restart_server()
                self._send_json(200, {"ok": ok, "message": msg, **ctrl.status_dict()})
            elif self.path == "/ctl/connect-app":
                if not ctrl.is_running():
                    ctrl.start_server()
                    time.sleep(1.2)
                try:
                    req = urllib.request.Request(
                        f"http://127.0.0.1:{SERVER_PORT}/api/handshake/laptop-ready",
                        method="POST",
                        data=b"{}",
                        headers={"Content-Type": "application/json"},
                    )
                    with urllib.request.urlopen(req, timeout=3) as resp:
                        self._send_bytes(200, "application/json; charset=utf-8", resp.read())
                except Exception as e:
                    self._send_json(200, {"phase": "ERROR", "error": str(e)})
            elif self.path == "/ctl/cancel-connect":
                if ctrl.is_running():
                    try:
                        req = urllib.request.Request(
                            f"http://127.0.0.1:{SERVER_PORT}/api/handshake/cancel",
                            method="POST",
                            data=b"{}",
                            headers={"Content-Type": "application/json"},
                        )
                        with urllib.request.urlopen(req, timeout=2):
                            pass
                    except Exception:
                        pass
                self._send_json(200, {"phase": "IDLE"})
            elif self.path == "/ctl/chat":
                if not ctrl.is_running():
                    ctrl.start_server()
                    time.sleep(1.0)
                try:
                    req = urllib.request.Request(
                        f"http://127.0.0.1:{SERVER_PORT}/api/chat",
                        method="POST",
                        data=json.dumps({"query": body.get("query", "")}).encode("utf-8"),
                        headers={"Content-Type": "application/json"},
                    )
                    with urllib.request.urlopen(req, timeout=5) as resp:
                        self._send_bytes(200, "application/json; charset=utf-8", resp.read())
                except Exception as e:
                    self._send_json(500, {"error": str(e)})
            elif self.path == "/ctl/publish":
                res = ctrl.publish_update(
                    body.get("title", "Server Code Update"),
                    body.get("changelog", "Updated from Studio"),
                    int(body.get("estimated_seconds", 12)),
                )
                self._send_json(200, res)
            elif self.path == "/ctl/reset-brain":
                ctrl.reset_brain()
                self._send_json(200, {"ok": True})
            elif self.path == "/ctl/firewall":
                cmd = (
                    "Start-Process powershell -Verb RunAs -ArgumentList "
                    "'netsh advfirewall firewall add rule name=\"Shadow Luna Server 8000\" "
                    "dir=in action=allow protocol=TCP localport=8000'"
                )
                subprocess.Popen(["powershell", "-NoProfile", "-Command", cmd])
                self._send_json(200, {"ok": True})
            elif self.path == "/ctl/focus":
                ctrl.focus_window()
                self._send_json(200, {"ok": True})
            else:
                self._send_json(404, {"error": "Unknown endpoint"})

    server = ThreadingHTTPServer(("127.0.0.1", CONTROL_PORT), ControlHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def apply_win32_window_branding():
    """Applies Immersive Dark Titlebar and custom Shadow Moon .ico directly on Win32 HWND."""
    for _ in range(15):
        hwnd = ctypes.windll.user32.FindWindowW(None, "Shadow")
        if hwnd:
            try:
                val = ctypes.c_int(1)
                if ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, 20, ctypes.byref(val), 4) != 0:
                    ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, 19, ctypes.byref(val), 4)
            except Exception:
                pass
            if os.path.exists(ICO_PATH):
                try:
                    hicon_big = ctypes.windll.user32.LoadImageW(None, ICO_PATH, 1, 256, 256, 0x10)
                    hicon_small = ctypes.windll.user32.LoadImageW(None, ICO_PATH, 1, 32, 32, 0x10)
                    if hicon_big:
                        ctypes.windll.user32.SendMessageW(hwnd, 0x0080, 1, hicon_big)
                    if hicon_small:
                        ctypes.windll.user32.SendMessageW(hwnd, 0x0080, 0, hicon_small)
                except Exception:
                    pass
            return
        time.sleep(0.15)


def main():
    # Crucial: set AppUserModelID BEFORE creating window so Windows Taskbar uses our Shadow Moon icon!
    try:
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("Shadow.Luna.DesktopApp.2026")
    except Exception:
        pass

    if focus_existing_instance():
        return

    ctrl = LunaServerController()
    http_ctl = start_control_http_server(ctrl)
    ctrl.append_log("Shadow Desktop started cleanly. Background server initializing...")

    # Start background server silently in parallel so user enters a ready app immediately
    threading.Thread(target=ctrl.start_server, daemon=True).start()

    window = webview.create_window(
        title="Shadow",
        url=f"http://127.0.0.1:{CONTROL_PORT}/studio",
        width=1060,
        height=720,
        min_size=(820, 580),
        background_color="#06060b",
    )
    ctrl.window = window

    def on_shown():
        threading.Thread(target=apply_win32_window_branding, daemon=True).start()

    def on_closing():
        if ctrl.is_running():
            ctrl.stop_server()
        try:
            ctrl.write_runtime_state(False)
            http_ctl.shutdown()
        except Exception:
            pass

    window.events.shown += on_shown
    window.events.closing += on_closing

    icon_arg = ICO_PATH if os.path.exists(ICO_PATH) else None
    webview.start(gui="edgechromium", icon=icon_arg)


if __name__ == "__main__":
    main()
