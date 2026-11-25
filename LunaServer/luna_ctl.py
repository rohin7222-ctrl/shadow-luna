"""
AI & CLI Control Bridge for Luna Server Studio (`luna_desktop.py`).
Allows both the AI assistant and scripts to query status, launch the Windows GUI app,
turn the server ON/OFF, push live updates, read logs, and test Luna chat in 1 command.
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PYTHONW_EXE = os.path.join(BASE_DIR, ".venv", "Scripts", "pythonw.exe")
DESKTOP_SCRIPT = os.path.join(BASE_DIR, "luna_desktop.py")
CTL_BASE = "http://127.0.0.1:8765"
SERVER_BASE = "http://127.0.0.1:8000"


def _req(url: str, method: str = "GET", body: dict = None, timeout: float = 10.0) -> dict:
    data = json.dumps(body).encode("utf-8") if body is not None else (b"{}" if method == "POST" else None)
    headers = {"Content-Type": "application/json"} if data is not None else {}
    req = urllib.request.Request(url, method=method, data=data, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def ensure_gui(wait_sec: float = 6.0) -> bool:
    try:
        _req(f"{CTL_BASE}/ctl/status", timeout=0.8)
        return True
    except Exception:
        pass

    exe = PYTHONW_EXE if os.path.exists(PYTHONW_EXE) else sys.executable
    flags = getattr(subprocess, "DETACHED_PROCESS", 0x00000008) | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0x00000200)
    subprocess.Popen([exe, DESKTOP_SCRIPT], cwd=BASE_DIR, creationflags=flags, close_fds=True)

    deadline = time.time() + wait_sec
    while time.time() < deadline:
        try:
            _req(f"{CTL_BASE}/ctl/status", timeout=0.6)
            return True
        except Exception:
            time.sleep(0.25)
    return False


def main():
    parser = argparse.ArgumentParser(description="Luna Server Studio AI/CLI Control")
    sub = parser.add_subparsers(dest="cmd", required=True)

    sub.add_parser("status", help="Inspect Windows App & Luna Server status")
    sub.add_parser("open", help="Launch or focus Luna Server Studio Windows App")
    sub.add_parser("on", help="Turn Luna Server ON inside Windows App")
    sub.add_parser("off", help="Turn Luna Server OFF inside Windows App")
    sub.add_parser("restart", help="Restart Luna Server inside Windows App")
    sub.add_parser("logs", help="Print recent live server logs")
    sub.add_parser("reset-brain", help="Reset Luna AI learned data to 0 memories")

    p_pub = sub.add_parser("publish", help="Push a new update to Shadow app")
    p_pub.add_argument("--title", default="Server Code Update")
    p_pub.add_argument("--changelog", default="Live update pushed by AI assistant")
    p_pub.add_argument("--seconds", type=int, default=12)

    p_chat = sub.add_parser("chat", help="Test Luna AI /api/chat directly")
    p_chat.add_argument("query")

    args = parser.parse_args()

    if args.cmd == "open":
        ok = ensure_gui()
        if ok:
            res = _req(f"{CTL_BASE}/ctl/focus", method="POST")
            print(json.dumps({"gui_open": True, **res}, indent=2))
        else:
            print(json.dumps({"gui_open": False, "error": "GUI failed to respond on port 8765"}, indent=2))
            sys.exit(1)

    elif args.cmd == "status":
        try:
            print(json.dumps(_req(f"{CTL_BASE}/ctl/status"), indent=2, ensure_ascii=False))
        except Exception:
            # Check if standalone server on 8000 is running without GUI
            try:
                st = _req(f"{SERVER_BASE}/api/state", timeout=1.0)
                print(json.dumps({"gui_running": False, "server_running": True, **st}, indent=2, ensure_ascii=False))
            except Exception:
                print(json.dumps({"gui_running": False, "server_running": False}, indent=2))

    elif args.cmd == "on":
        ensure_gui()
        print(json.dumps(_req(f"{CTL_BASE}/ctl/start", method="POST"), indent=2, ensure_ascii=False))

    elif args.cmd == "off":
        try:
            print(json.dumps(_req(f"{CTL_BASE}/ctl/stop", method="POST"), indent=2, ensure_ascii=False))
        except Exception:
            print(json.dumps({"ok": True, "gui_running": False, "server_running": False, "message": "Studio already closed"}, indent=2))

    elif args.cmd == "restart":
        ensure_gui()
        print(json.dumps(_req(f"{CTL_BASE}/ctl/restart", method="POST"), indent=2, ensure_ascii=False))

    elif args.cmd == "publish":
        ensure_gui()
        res = _req(
            f"{CTL_BASE}/ctl/publish",
            method="POST",
            body={"title": args.title, "changelog": args.changelog, "estimated_seconds": args.seconds},
        )
        print(json.dumps(res, indent=2, ensure_ascii=False))

    elif args.cmd == "logs":
        res = _req(f"{CTL_BASE}/ctl/logs")
        for line in res.get("logs", [])[-50:]:
            print(line)

    elif args.cmd == "reset-brain":
        ensure_gui()
        print(json.dumps(_req(f"{CTL_BASE}/ctl/reset-brain", method="POST"), indent=2))

    elif args.cmd == "chat":
        res = _req(f"{SERVER_BASE}/api/chat", method="POST", body={"query": args.query})
        print(json.dumps(res, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
