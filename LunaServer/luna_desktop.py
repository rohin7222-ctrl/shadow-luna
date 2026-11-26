import base64
import ctypes
import hashlib
import json
import os
import shutil
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.request
from collections import deque
from contextlib import closing
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


def get_all_local_ips() -> list[str]:
    ips = []
    try:
        for ip in socket.gethostbyname_ex(socket.gethostname())[2]:
            if not ip.startswith("127.") and not ip.startswith("169.254.") and ip not in ips:
                ips.append(ip)
    except Exception:
        pass
    primary = get_wifi_ip()
    if primary and not primary.startswith("127.") and primary not in ips:
        ips.insert(0, primary)
    return ips or ["127.0.0.1"]


_fw_lock = threading.Lock()
_fw_cache = {"checked_at": 0.0, "refreshing": False, "data": {"rule_exists": False, "wifi_profile": "Unknown", "blocked_likely": False}}


def _probe_firewall_now() -> dict:
    no_window = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)
    rule_ok = False
    profile = "Unknown"
    try:
        r = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "if (Get-NetFirewallRule -DisplayName 'Shadow Luna Server 8000' -ErrorAction SilentlyContinue | Where-Object { $_.Enabled -eq 'True' }) { 'OK' }"],
            capture_output=True, text=True, timeout=4.0, creationflags=no_window
        )
        if "OK" in (r.stdout or ""):
            rule_ok = True
        else:
            r2 = subprocess.run(
                ["netsh", "advfirewall", "firewall", "show", "rule", "name=Shadow Luna Server 8000"],
                capture_output=True, text=True, timeout=2.5, creationflags=no_window
            )
            out = r2.stdout or ""
            if "8000" in out and ("Yes" in out or "True" in out or "Enabled" in out):
                rule_ok = True
    except Exception:
        pass
    try:
        p = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "(Get-NetConnectionProfile -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty NetworkCategory)"],
            capture_output=True, text=True, timeout=3.0, creationflags=no_window
        )
        cat = (p.stdout or "").strip()
        if cat:
            profile = cat
    except Exception:
        pass
    res = {
        "rule_exists": rule_ok,
        "wifi_profile": profile,
        "blocked_likely": (not rule_ok),
    }
    with _fw_lock:
        _fw_cache["checked_at"] = time.time()
        _fw_cache["data"] = res
        _fw_cache["refreshing"] = False
    return res


def check_firewall_status(force: bool = False) -> dict:
    if force:
        return _probe_firewall_now()
    now = time.time()
    with _fw_lock:
        stale = (now - _fw_cache["checked_at"]) >= 8.0
        already = _fw_cache["refreshing"]
        cached = dict(_fw_cache["data"])
        if stale and not already:
            _fw_cache["refreshing"] = True
            threading.Thread(target=_probe_firewall_now, daemon=True).start()
    return cached


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
        self.lock = threading.RLock()
        self.log_buffer = deque(maxlen=400)
        self.wifi_ip = get_wifi_ip()
        self.window = None
        self._closing = False
        self._last_good_snap = None

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
            if self._closing:
                return False, "Studio shutting down"
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
            try:
                if proc.stdout:
                    proc.stdout.close()
            except Exception:
                pass
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
        with self.lock:
            if self._closing:
                return False, "Studio shutting down"
            self.stop_server()
            time.sleep(0.45)
            if self._closing:
                return False, "Studio shutting down"
            return self.start_server()

    def wait_until_ready(self, timeout_sec: float = 4.5) -> bool:
        deadline = time.time() + timeout_sec
        while time.time() < deadline:
            if is_port_open("127.0.0.1", SERVER_PORT, timeout=0.25):
                return True
            time.sleep(0.15)
        return is_port_open("127.0.0.1", SERVER_PORT, timeout=0.25)

    def get_db_snapshot(self) -> dict:
        if not os.path.exists(DB_PATH):
            return {
                "memories_count": 0, "nodes_count": 0, "tokens_count": 0, "db_size_kb": 0.0,
                "latest_version": "2.2.0", "memories": [], "nodes": [], "tokens": [], "sessions": [], "transitions": [], "updates": []
            }
        try:
            with closing(sqlite3.connect(DB_PATH, timeout=15.0)) as conn:
                conn.row_factory = sqlite3.Row
                c = conn.cursor()
                mem_count = c.execute("SELECT COUNT(*) FROM memories").fetchone()[0]
                node_count = c.execute("SELECT COUNT(*) FROM graph_nodes").fetchone()[0]
                tok_count = c.execute("SELECT COUNT(*) FROM learned_tokens").fetchone()[0]
                updates_raw = [dict(r) for r in c.execute("SELECT * FROM app_updates").fetchall()]
                updates = sorted(
                    updates_raw,
                    key=lambda r: (tuple(int(x) if x.isdigit() else 0 for x in (r["version"] or "0").split(".")[:3]), r["released_at"]),
                    reverse=True,
                )[:20]
                memories = [dict(r) for r in c.execute("SELECT * FROM memories ORDER BY timestamp DESC LIMIT 40").fetchall()]
                nodes = [dict(r) for r in c.execute("SELECT * FROM graph_nodes ORDER BY mention_count DESC LIMIT 30").fetchall()]
                tokens = []
                sessions = []
                transitions = []
                try:
                    tokens = [dict(r) for r in c.execute("SELECT * FROM learned_tokens ORDER BY frequency DESC, last_seen DESC LIMIT 150").fetchall()]
                except Exception:
                    pass
                try:
                    sessions = [dict(r) for r in c.execute("SELECT * FROM sync_sessions ORDER BY synced_at DESC LIMIT 10").fetchall()]
                except Exception:
                    pass
                try:
                    transitions = [dict(r) for r in c.execute("SELECT * FROM learned_transitions ORDER BY weight DESC, transition_count DESC LIMIT 20").fetchall()]
                except Exception:
                    pass
            db_bytes = os.path.getsize(DB_PATH)
            latest_ver = updates[0]["version"] if updates else "2.2.0"
            snap = {
                "memories_count": mem_count,
                "nodes_count": node_count,
                "tokens_count": tok_count,
                "db_size_kb": round(db_bytes / 1024.0, 1),
                "latest_version": latest_ver,
                "memories": memories,
                "nodes": nodes,
                "tokens": tokens,
                "sessions": sessions,
                "transitions": transitions,
                "updates": updates,
            }
            self._last_good_snap = snap
            return snap
        except Exception as e:
            if self._last_good_snap:
                return dict(self._last_good_snap, error=str(e))
            return {
                "memories_count": 0, "nodes_count": 0, "tokens_count": 0, "db_size_kb": 0.0,
                "latest_version": "?", "memories": [], "nodes": [], "tokens": [], "sessions": [], "transitions": [], "updates": [], "error": str(e)
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

        srv_hash = "manual"
        srv_file = os.path.join(BASE_DIR, "luna_server.py")
        if os.path.exists(srv_file):
            try:
                with open(srv_file, "rb") as sf:
                    srv_hash = hashlib.sha256(sf.read()).hexdigest()[:16]
            except Exception:
                pass

        with closing(sqlite3.connect(DB_PATH, timeout=15.0)) as conn:
            conn.row_factory = sqlite3.Row
            c = conn.cursor()
            c.execute(
                "CREATE TABLE IF NOT EXISTS app_updates ("
                "version TEXT PRIMARY KEY, title TEXT NOT NULL, changelog TEXT NOT NULL, "
                "released_at INTEGER NOT NULL, estimated_seconds INTEGER DEFAULT 12, code_hash TEXT)"
            )
            rows = c.execute("SELECT version, released_at FROM app_updates").fetchall()
            existing = {r["version"] for r in rows}
            if rows:
                rows_sorted = sorted(
                    rows,
                    key=lambda r: tuple(int(x) if x.isdigit() else 0 for x in (r["version"] or "0").split(".")[:3]),
                    reverse=True,
                )
                latest = rows_sorted[0]["version"]
            else:
                latest = "2.2.0"
            parts = [int(x) if x.isdigit() else 0 for x in latest.split(".")]
            while len(parts) < 3:
                parts.append(0)
            next_ver = f"{parts[0]}.{parts[1]}.{parts[2] + 1}"
            while next_ver in existing:
                parts[2] += 1
                next_ver = f"{parts[0]}.{parts[1]}.{parts[2] + 1}"
            now_ms = int(time.time() * 1000)
            c.execute(
                "INSERT INTO app_updates (version, title, changelog, released_at, estimated_seconds, code_hash) VALUES (?, ?, ?, ?, ?, ?)",
                (next_ver, title, changelog, now_ms, max(5, estimated_seconds), srv_hash),
            )
            conn.commit()
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
            with closing(sqlite3.connect(DB_PATH, timeout=15.0)) as conn:
                for tbl in ("memories", "graph_nodes", "graph_edges", "user_state", "learned_tokens", "sync_sessions", "learned_transitions"):
                    try:
                        conn.execute(f"DELETE FROM {tbl}")
                    except Exception:
                        pass
                conn.commit()
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
            path_only = self.path.split("?", 1)[0]
            if path_only == "/studio" or path_only == "/":
                try:
                    with open(STUDIO_HTML_PATH, "rb") as f:
                        self._send_bytes(200, "text/html; charset=utf-8", f.read())
                except Exception as e:
                    self._send_json(500, {"error": str(e)})
            elif path_only == "/studio/logo.png":
                try:
                    with open(PNG_LOGO_PATH, "rb") as f:
                        self._send_bytes(200, "image/png", f.read())
                except Exception:
                    self._send_json(404, {"error": "Logo missing"})
            elif path_only == "/ctl/studio-data":
                running = ctrl.is_running()
                ctrl.wifi_ip = get_wifi_ip()
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
                        "all_ips": get_all_local_ips(),
                        "firewall": check_firewall_status(),
                        "snapshot": snap,
                        "handshake": hs,
                    },
                )
            elif path_only == "/ctl/status":
                self._send_json(200, ctrl.status_dict())
            elif path_only == "/ctl/logs":
                self._send_json(200, {"logs": list(ctrl.log_buffer)})
            else:
                self._send_json(404, {"error": "Unknown endpoint"})

        def do_POST(self):
            path_only = self.path.split("?", 1)[0]
            try:
                length = max(0, min(int(self.headers.get("Content-Length", "0") or 0), 2_000_000))
            except Exception:
                length = 0
            raw_body = self.rfile.read(length).decode("utf-8", errors="replace") if length > 0 else "{}"
            try:
                parsed = json.loads(raw_body) if raw_body.strip() else {}
                body = parsed if isinstance(parsed, dict) else {}
            except Exception:
                body = {}

            if path_only == "/ctl/start":
                ok, msg = ctrl.start_server()
                self._send_json(200, {"ok": ok, "message": msg, **ctrl.status_dict()})
            elif path_only == "/ctl/stop":
                ok, msg = ctrl.stop_server()
                self._send_json(200, {"ok": ok, "message": msg, **ctrl.status_dict()})
            elif path_only == "/ctl/restart":
                ok, msg = ctrl.restart_server()
                self._send_json(200, {"ok": ok, "message": msg, **ctrl.status_dict()})
            elif path_only == "/ctl/connect-app":
                if not ctrl.is_running():
                    ctrl.start_server()
                    ctrl.wait_until_ready(4.5)
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
                    self._send_json(503, {"phase": "ERROR", "error": str(e)})
            elif path_only == "/ctl/cancel-connect":
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
            elif path_only == "/ctl/chat":
                if not ctrl.is_running():
                    ctrl.start_server()
                    ctrl.wait_until_ready(4.5)
                try:
                    req = urllib.request.Request(
                        f"http://127.0.0.1:{SERVER_PORT}/api/chat",
                        method="POST",
                        data=json.dumps({"query": str(body.get("query", ""))}).encode("utf-8"),
                        headers={"Content-Type": "application/json"},
                    )
                    with urllib.request.urlopen(req, timeout=5) as resp:
                        self._send_bytes(200, "application/json; charset=utf-8", resp.read())
                except Exception as e:
                    self._send_json(500, {"error": str(e)})
            elif path_only == "/ctl/publish":
                try:
                    sec = int(body.get("estimated_seconds", 12))
                except Exception:
                    sec = 12
                res = ctrl.publish_update(
                    str(body.get("title", "Server Code Update")),
                    str(body.get("changelog", "Updated from Studio")),
                    sec,
                )
                self._send_json(200, res)
            elif path_only == "/ctl/reset-brain":
                ctrl.reset_brain()
                self._send_json(200, {"ok": True})
            elif path_only == "/ctl/firewall":
                py_exes = list(dict.fromkeys([
                    PYTHON_EXE,
                    sys.executable,
                    getattr(sys, "_base_executable", ""),
                ]))
                exe_cmds = []
                for idx, p_exe in enumerate(py_exes):
                    if p_exe and os.path.exists(p_exe):
                        safe_path = p_exe.replace("'", "''")
                        rname = f"Shadow Luna Python {idx}"
                        exe_cmds.append(
                            f"Remove-NetFirewallRule -DisplayName '{rname}' -ErrorAction SilentlyContinue; "
                            f"New-NetFirewallRule -DisplayName '{rname}' -Direction Inbound -Action Allow -Program '{safe_path}' -Profile Any -Enabled True | Out-Null"
                        )
                ps_script = "\n".join([
                    "Remove-NetFirewallRule -DisplayName 'Shadow Luna Server 8000' -ErrorAction SilentlyContinue",
                    "New-NetFirewallRule -DisplayName 'Shadow Luna Server 8000' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8000 -Profile Any -Enabled True | Out-Null",
                    "Remove-NetFirewallRule -DisplayName 'Shadow Luna Discovery 8800' -ErrorAction SilentlyContinue",
                    "New-NetFirewallRule -DisplayName 'Shadow Luna Discovery 8800' -Direction Inbound -Action Allow -Protocol UDP -LocalPort 8800 -Profile Any -Enabled True | Out-Null",
                    *exe_cmds,
                ])
                encoded = base64.b64encode(ps_script.encode("utf-16-le")).decode("ascii")
                uac_cmd = f"Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile','-EncodedCommand','{encoded}'"
                try:
                    no_window = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)
                    proc_fw = subprocess.run(
                        ["powershell", "-NoProfile", "-Command", uac_cmd],
                        capture_output=True, text=True, timeout=75, creationflags=no_window
                    )
                    fw = check_firewall_status(force=True)
                    if fw.get("rule_exists"):
                        self._send_json(200, {"ok": True, "firewall": fw, "message": "Firewall allowed on ports 8000 & 8800!"})
                    else:
                        self._send_json(200, {
                            "ok": False,
                            "firewall": fw,
                            "message": f"Admin prompt cancelled or blocked (code {proc_fw.returncode}). Click Allow & approve Yes."
                        })
                except Exception as e:
                    self._send_json(500, {"ok": False, "error": str(e), "message": f"Firewall setup timed out or failed: {e}"})
            elif path_only == "/ctl/adb-bridge":
                candidates = [
                    shutil.which("adb"),
                    os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb.exe"),
                    os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"),
                ]
                adb_exe = next((p for p in candidates if p and os.path.exists(p)), "adb")
                try:
                    no_window = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)
                    dev_proc = subprocess.run(
                        [adb_exe, "devices"],
                        capture_output=True, text=True, timeout=10, creationflags=no_window
                    )
                    serials = [
                        line.split()[0]
                        for line in (dev_proc.stdout or "").splitlines()[1:]
                        if len(line.split()) >= 2 and line.split()[1] == "device"
                    ]
                    target_args = ["-s", serials[0]] if serials else []
                    r = subprocess.run(
                        [adb_exe, *target_args, "reverse", f"tcp:{SERVER_PORT}", f"tcp:{SERVER_PORT}"],
                        capture_output=True, text=True, timeout=10, creationflags=no_window
                    )
                    if r.returncode == 0:
                        self._send_json(200, {"ok": True, "message": "USB Cable Bridge Ready! On phone, tap Update URL -> http://127.0.0.1:8000"})
                    else:
                        self._send_json(200, {"ok": False, "message": (r.stderr or r.stdout or "No USB debugging phone detected.").strip()})
                except Exception as e:
                    self._send_json(200, {"ok": False, "message": f"ADB unavailable: {e}"})
            elif path_only == "/ctl/focus":
                ctrl.focus_window()
                self._send_json(200, {"ok": True})
            else:
                self._send_json(404, {"error": "Unknown endpoint"})

    class ExclusiveThreadingHTTPServer(ThreadingHTTPServer):
        allow_reuse_address = False

    server = ExclusiveThreadingHTTPServer(("127.0.0.1", CONTROL_PORT), ControlHandler)
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
    try:
        http_ctl = start_control_http_server(ctrl)
    except OSError:
        focus_existing_instance()
        return
    ctrl.append_log("Shadow Desktop started cleanly. Background server initializing...")

    # Start fresh background server silently (replacing any stale port-8000 process)
    threading.Thread(target=ctrl.restart_server, daemon=True).start()

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
        with ctrl.lock:
            ctrl._closing = True
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
