import hashlib
import json
import os
import re
import socket
import sqlite3
import threading
import time
from contextlib import closing
from datetime import datetime
from typing import List, Optional
from fastapi import FastAPI, Query, Request
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

# Change notes for the current build of LunaServer.
# Whenever luna_server.py changes on disk, register_server_release() detects the SHA-256 change
# and automatically publishes a new timestamped update to the Shadow mobile app!
CURRENT_RELEASE_TITLE = "Blank-Slate AI, Minimal Main Chat & Live OTA Updates"
CURRENT_RELEASE_CHANGELOG = (
    "• Removed all pre-built AI knowledge and canned replies\n"
    "• Clean empty Main Page to test real learned responses\n"
    "• Added 3-dot top menu with Updates & Learning Permission slides\n"
    "• Enabled server-driven background updates with countdown popup"
)
BASE_VERSION = "2.2.0"

app = FastAPI(title="Luna Cognitive Core", version=BASE_VERSION)

STATIC_DIR = os.path.join(os.path.dirname(__file__), "static")
os.makedirs(STATIC_DIR, exist_ok=True)
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

DB_PATH = os.path.join(os.path.dirname(__file__), "luna_memory.db")


def open_db(row_factory=None) -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=15.0)
    if row_factory is not None:
        conn.row_factory = row_factory
    return conn


def compute_server_hash() -> str:
    try:
        with open(__file__, "rb") as f:
            return hashlib.sha256(f.read()).hexdigest()[:16]
    except Exception:
        return "unknown"


def parse_version_tuple(v: str):
    parts = []
    for p in (v or "0.0.0").strip().lstrip("v").split("."):
        try:
            parts.append(int(p))
        except ValueError:
            parts.append(0)
    while len(parts) < 3:
        parts.append(0)
    return tuple(parts[:3])


def bump_patch_version(v: str) -> str:
    major, minor, patch = parse_version_tuple(v)
    return f"{major}.{minor}.{patch + 1}"


def init_db():
    with closing(open_db()) as conn:
        conn.execute("PRAGMA journal_mode=WAL")
        c = conn.cursor()
        # 1. Episodic Raw Memories (populated strictly from user's Shadow app)
        c.execute('''
            CREATE TABLE IF NOT EXISTS memories (
                id TEXT PRIMARY KEY,
                source_type TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                text_content TEXT,
                metadata_json TEXT,
                processed_flag INTEGER DEFAULT 0,
                created_at INTEGER
            )
        ''')
        # 2. Knowledge Graph Nodes
        c.execute('''
            CREATE TABLE IF NOT EXISTS graph_nodes (
                id TEXT PRIMARY KEY,
                label TEXT NOT NULL,
                entity_type TEXT NOT NULL,
                mention_count INTEGER DEFAULT 1,
                last_seen INTEGER
            )
        ''')
        # 3. Knowledge Graph Edges
        c.execute('''
            CREATE TABLE IF NOT EXISTS graph_edges (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_id TEXT NOT NULL,
                target_id TEXT NOT NULL,
                relation TEXT NOT NULL,
                weight REAL DEFAULT 1.0,
                timestamp INTEGER
            )
        ''')
        # 4. User Latent State
        c.execute('''
            CREATE TABLE IF NOT EXISTS user_state (
                key TEXT PRIMARY KEY,
                value TEXT,
                updated_at INTEGER
            )
        ''')
        # 5. Learned Token Frequencies (purely from synced memories)
        c.execute('''
            CREATE TABLE IF NOT EXISTS learned_tokens (
                token TEXT PRIMARY KEY,
                frequency INTEGER DEFAULT 1,
                last_source TEXT,
                last_seen INTEGER
            )
        ''')
        # 6. Server-Published App Updates Registry
        c.execute('''
            CREATE TABLE IF NOT EXISTS app_updates (
                version TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                changelog TEXT NOT NULL,
                released_at INTEGER NOT NULL,
                estimated_seconds INTEGER DEFAULT 12,
                code_hash TEXT
            )
        ''')
        # 7. Handshake Sync History (permanent details of uploaded mobile learning)
        c.execute('''
            CREATE TABLE IF NOT EXISTS sync_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                synced_at INTEGER NOT NULL,
                records_added INTEGER DEFAULT 0,
                tokens_added INTEGER DEFAULT 0,
                summary TEXT
            )
        ''')
        conn.commit()


handshake_lock = threading.Lock()
handshake_state = {
    "laptop_ready": False,
    "laptop_ready_at": 0,
    "mobile_ready": False,
    "mobile_ready_at": 0,
    "phase": "IDLE",  # IDLE | WAITING_FOR_MOBILE | WAITING_FOR_LAPTOP | TRANSFERRING | COMPLETED | CANCELLED
    "total_records": 0,
    "received_records": 0,
    "received_tokens": 0,
    "last_message": "",
    "completed_at": 0,
}


def _refresh_handshake_locked(now_ms: int):
    # Auto-expire waiting flags after 120s so stale sessions don't get stuck
    if handshake_state["phase"] in ("WAITING_FOR_MOBILE", "WAITING_FOR_LAPTOP"):
        if handshake_state["laptop_ready"] and now_ms - handshake_state["laptop_ready_at"] > 120_000:
            handshake_state["laptop_ready"] = False
        if handshake_state["mobile_ready"] and now_ms - handshake_state["mobile_ready_at"] > 120_000:
            handshake_state["mobile_ready"] = False
        if not handshake_state["laptop_ready"] and not handshake_state["mobile_ready"]:
            handshake_state["phase"] = "IDLE"
            handshake_state["last_message"] = ""
    elif handshake_state["phase"] == "COMPLETED":
        if not handshake_state["laptop_ready"] and not handshake_state["mobile_ready"]:
            if handshake_state.get("completed_at", 0) > 0 and now_ms - handshake_state["completed_at"] > 15_000:
                handshake_state["phase"] = "IDLE"


def register_server_release():
    code_hash = compute_server_hash()
    now_ms = int(time.time() * 1000)
    with closing(open_db(sqlite3.Row)) as conn:
        c = conn.cursor()

        rows = c.execute("SELECT * FROM app_updates ORDER BY released_at DESC").fetchall()
        if not rows:
            c.execute(
                "INSERT INTO app_updates (version, title, changelog, released_at, estimated_seconds, code_hash) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (BASE_VERSION, CURRENT_RELEASE_TITLE, CURRENT_RELEASE_CHANGELOG, now_ms, 12, code_hash)
            )
        else:
            latest = rows[0]
            if latest["code_hash"] != code_hash:
                next_version = bump_patch_version(latest["version"])
                # Ensure uniqueness if clock/version collides
                existing_versions = {r["version"] for r in rows}
                while next_version in existing_versions:
                    next_version = bump_patch_version(next_version)
                note_file = os.path.join(os.path.dirname(__file__), "release_note.txt")
                custom_note = None
                if os.path.exists(note_file):
                    try:
                        with open(note_file, "r", encoding="utf-8") as nf:
                            custom_note = nf.read().strip()
                    except Exception:
                        custom_note = None
                title = CURRENT_RELEASE_TITLE if not custom_note else f"Update v{next_version}"
                changelog = custom_note or (
                    f"• Code update synced from LunaServer at {datetime.now().strftime('%I:%M %p')}\n"
                    f"• Build signature: {code_hash}\n"
                    f"• Live UI/UX & Cognitive Engine refreshed"
                )
                c.execute(
                    "INSERT INTO app_updates (version, title, changelog, released_at, estimated_seconds, code_hash) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    (next_version, title, changelog, now_ms, 12, code_hash)
                )
        conn.commit()


init_db()
register_server_release()


# ─── Blank-Slate Cognitive Engine (Learns Strictly From Synced User Data) ───
class CognitiveEngine:
    @staticmethod
    def tokenize(text: str) -> List[str]:
        if not text:
            return []
        # Extract English/Unicode Tamil tokens of length >= 2 containing alphanumeric characters
        tokens = re.findall(r"[\w\u0B80-\u0BFF]+", text.lower(), flags=re.UNICODE)
        return [t for t in tokens if len(t) >= 2 and any(ch.isalnum() for ch in t)]

    @staticmethod
    def learn_from_record(record: dict, conn: sqlite3.Connection):
        c = conn.cursor()
        source = record.get("source_type", "")
        text = record.get("text_content") or ""
        ts = record.get("timestamp", int(time.time() * 1000))
        try:
            meta = json.loads(record.get("metadata_json") or "{}")
        except Exception:
            meta = {}

        # 1. Token learning purely from user text
        for tok in CognitiveEngine.tokenize(text):
            c.execute(
                "INSERT INTO learned_tokens (token, frequency, last_source, last_seen) VALUES (?, 1, ?, ?) "
                "ON CONFLICT(token) DO UPDATE SET frequency = frequency + 1, last_source = ?, last_seen = ?",
                (tok, source, ts, source, ts)
            )

        # 2. Extract App node
        pkg = meta.get("package") or meta.get("package_name")
        if pkg:
            app_name = pkg.split(".")[-1].capitalize()
            c.execute(
                "INSERT INTO graph_nodes (id, label, entity_type, last_seen) VALUES (?, ?, ?, ?) "
                "ON CONFLICT(id) DO UPDATE SET mention_count = mention_count + 1, last_seen = ?",
                (f"app:{pkg}", app_name, "APP", ts, ts)
            )

        # 3. Extract Person node from Notifications / Contacts
        title = meta.get("title") or meta.get("display_name")
        if title and source in ("NOTIFICATION", "CONTACT"):
            c.execute(
                "INSERT INTO graph_nodes (id, label, entity_type, last_seen) VALUES (?, ?, ?, ?) "
                "ON CONFLICT(id) DO UPDATE SET mention_count = mention_count + 1, last_seen = ?",
                (f"person:{title}", title, "PERSON", ts, ts)
            )
            if pkg:
                existing_edge = c.execute(
                    "SELECT id FROM graph_edges WHERE source_id = ? AND target_id = ? AND relation = ? LIMIT 1",
                    (f"person:{title}", f"app:{pkg}", "INTERACTED_VIA")
                ).fetchone()
                if existing_edge:
                    c.execute(
                        "UPDATE graph_edges SET weight = MIN(100.0, weight + 1.0), timestamp = MAX(timestamp, ?) WHERE id = ?",
                        (ts, existing_edge[0])
                    )
                else:
                    c.execute(
                        "INSERT INTO graph_edges (source_id, target_id, relation, timestamp) VALUES (?, ?, ?, ?)",
                        (f"person:{title}", f"app:{pkg}", "INTERACTED_VIA", ts)
                    )

        # 4. Extract Media nodes (Photos / Screenshots)
        if source == "PHOTO":
            is_screenshot = meta.get("is_screenshot", False)
            node_type = "SCREENSHOT" if is_screenshot else "PHOTO"
            c.execute(
                "INSERT INTO graph_nodes (id, label, entity_type, last_seen) VALUES (?, ?, ?, ?) "
                "ON CONFLICT(id) DO UPDATE SET mention_count = mention_count + 1, last_seen = ?",
                (f"media:{record['id']}", text[:80], node_type, ts, ts)
            )

        # 5. Update Latent User State
        c.execute(
            "INSERT INTO user_state (key, value, updated_at) VALUES ('last_active_source', ?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = ?, updated_at = ?",
            (source, ts, source, ts)
        )
        if title:
            c.execute(
                "INSERT INTO user_state (key, value, updated_at) VALUES ('last_interacted_with', ?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value = ?, updated_at = ?",
                (title, ts, title, ts)
            )


# ─── API Schemas ───
class SyncRecord(BaseModel):
    id: str
    source_type: str
    timestamp: int
    text_content: Optional[str] = None
    metadata_json: Optional[str] = "{}"


class SyncToken(BaseModel):
    token: str
    frequency: int = 1
    last_source: str = "DEVICE"
    last_seen: int = 0


class SyncPayload(BaseModel):
    client_version: Optional[str] = "2.2.3"
    records: List[SyncRecord]
    tokens: List[SyncToken] = []


class HandshakeProgressRequest(BaseModel):
    total_records: int = 0
    received_records: int = 0
    received_tokens: int = 0


class ChatRequest(BaseModel):
    query: str


class PublishUpdateRequest(BaseModel):
    title: str
    changelog: str
    estimated_seconds: int = 12
    version: Optional[str] = None


DISCOVERY_PORT = 8800


def _udp_discovery_loop():
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("0.0.0.0", DISCOVERY_PORT))
    except Exception:
        return
    reply = json.dumps({"service": "shadow-luna", "port": 8000, "version": BASE_VERSION}).encode("utf-8")
    while True:
        try:
            data, addr = sock.recvfrom(512)
            if b"SHADOW_DISCOVER" in data:
                sock.sendto(reply, addr)
        except Exception:
            time.sleep(0.2)


@app.on_event("startup")
def start_lan_discovery():
    threading.Thread(target=_udp_discovery_loop, daemon=True).start()


@app.get("/api/ping")
def api_ping():
    with handshake_lock:
        phase = handshake_state["phase"]
    return {"service": "shadow-luna", "port": 8000, "version": BASE_VERSION, "phase": phase}


# ─── 2-Way Handshake Endpoints ("Connect to Server" <-> "Connect to App") ───
@app.post("/api/handshake/laptop-ready")
def handshake_laptop_ready(request: Request):
    client_host = request.client.host if request.client else ""
    if client_host not in ("127.0.0.1", "::1", "localhost"):
        return JSONResponse(status_code=403, content={"error": "Laptop handshake must originate locally"})
    now_ms = int(time.time() * 1000)
    with handshake_lock:
        _refresh_handshake_locked(now_ms)
        handshake_state["laptop_ready"] = True
        handshake_state["laptop_ready_at"] = now_ms
        if handshake_state["mobile_ready"]:
            handshake_state["phase"] = "TRANSFERRING"
            handshake_state["total_records"] = 0
            handshake_state["received_records"] = 0
            handshake_state["received_tokens"] = 0
            handshake_state["last_message"] = "Downloading learning progress..."
        else:
            handshake_state["phase"] = "WAITING_FOR_MOBILE"
            handshake_state["total_records"] = 0
            handshake_state["received_records"] = 0
            handshake_state["received_tokens"] = 0
            handshake_state["last_message"] = "Waiting for Mobile App (Tap ⋮ → Connect to Server)"
        return dict(handshake_state)


@app.post("/api/handshake/mobile-ready")
def handshake_mobile_ready():
    now_ms = int(time.time() * 1000)
    with handshake_lock:
        _refresh_handshake_locked(now_ms)
        handshake_state["mobile_ready"] = True
        handshake_state["mobile_ready_at"] = now_ms
        if handshake_state["laptop_ready"]:
            handshake_state["phase"] = "TRANSFERRING"
            handshake_state["total_records"] = 0
            handshake_state["received_records"] = 0
            handshake_state["received_tokens"] = 0
            handshake_state["last_message"] = "Both connected! Uploading learning progress..."
        else:
            handshake_state["phase"] = "WAITING_FOR_LAPTOP"
            handshake_state["total_records"] = 0
            handshake_state["received_records"] = 0
            handshake_state["received_tokens"] = 0
            handshake_state["last_message"] = "Waiting for Laptop (Click 'Connect to App' on Laptop)"
        return dict(handshake_state)


@app.get("/api/handshake/status")
def handshake_status():
    now_ms = int(time.time() * 1000)
    with handshake_lock:
        _refresh_handshake_locked(now_ms)
        return dict(handshake_state)


@app.post("/api/handshake/progress")
def handshake_progress(req: HandshakeProgressRequest):
    with handshake_lock:
        handshake_state["phase"] = "TRANSFERRING"
        handshake_state["total_records"] = req.total_records
        handshake_state["received_records"] = req.received_records
        handshake_state["received_tokens"] = req.received_tokens
        handshake_state["last_message"] = (
            f"Transferring {req.received_records}/{req.total_records} memories • {req.received_tokens} tokens"
        )
        return dict(handshake_state)


@app.post("/api/handshake/complete")
def handshake_complete(req: HandshakeProgressRequest):
    now_ms = int(time.time() * 1000)
    summary = f"Synced {req.received_records} memories & {req.received_tokens} tokens from Mobile"
    with closing(open_db()) as conn:
        c = conn.cursor()
        c.execute(
            "INSERT INTO sync_sessions (synced_at, records_added, tokens_added, summary) VALUES (?, ?, ?, ?)",
            (now_ms, req.received_records, req.received_tokens, summary)
        )
        conn.commit()

    with handshake_lock:
        handshake_state["laptop_ready"] = False
        handshake_state["mobile_ready"] = False
        handshake_state["phase"] = "COMPLETED"
        handshake_state["total_records"] = req.total_records
        handshake_state["received_records"] = req.received_records
        handshake_state["received_tokens"] = req.received_tokens
        handshake_state["completed_at"] = now_ms
        handshake_state["last_message"] = summary
        return dict(handshake_state)


@app.post("/api/handshake/cancel")
def handshake_cancel():
    with handshake_lock:
        handshake_state["laptop_ready"] = False
        handshake_state["mobile_ready"] = False
        handshake_state["phase"] = "IDLE"
        handshake_state["last_message"] = "Cancelled"
        return dict(handshake_state)


# ─── Endpoints ───
@app.post("/api/sync")
def sync_memories(payload: SyncPayload):
    saved = 0
    tokens_saved = 0
    now = int(time.time() * 1000)

    with closing(open_db()) as conn:
        c = conn.cursor()
        for r in payload.records:
            c.execute(
                "INSERT OR IGNORE INTO memories (id, source_type, timestamp, text_content, metadata_json, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (r.id, r.source_type, r.timestamp, r.text_content, r.metadata_json, now)
            )
            if c.rowcount > 0:
                saved += 1
                CognitiveEngine.learn_from_record(r.model_dump(), conn)

        for tok in payload.tokens:
            t_clean = tok.token.strip().lower()
            if len(t_clean) < 2:
                continue
            c.execute(
                "INSERT INTO learned_tokens (token, frequency, last_source, last_seen) VALUES (?, ?, ?, ?) "
                "ON CONFLICT(token) DO UPDATE SET frequency = MAX(frequency, ?), last_source = ?, last_seen = MAX(last_seen, ?)",
                (t_clean, max(1, tok.frequency), tok.last_source, tok.last_seen or now,
                 max(1, tok.frequency), tok.last_source, tok.last_seen or now)
            )
            tokens_saved += 1

        conn.commit()
    return {
        "status": "SUCCESS",
        "synced_records": saved,
        "synced_tokens": tokens_saved,
        "total_received": len(payload.records)
    }


@app.post("/api/reset-brain")
def reset_brain():
    """Wipes all learned memories, tokens, and graph state back to 0 (Tabula Rasa)."""
    with closing(open_db()) as conn:
        c = conn.cursor()
        c.execute("DELETE FROM memories")
        c.execute("DELETE FROM graph_nodes")
        c.execute("DELETE FROM graph_edges")
        c.execute("DELETE FROM user_state")
        c.execute("DELETE FROM learned_tokens")
        c.execute("DELETE FROM sync_sessions")
        conn.commit()
    with handshake_lock:
        handshake_state["laptop_ready"] = False
        handshake_state["mobile_ready"] = False
        handshake_state["phase"] = "IDLE"
        handshake_state["total_records"] = 0
        handshake_state["received_records"] = 0
        handshake_state["received_tokens"] = 0
        handshake_state["last_message"] = ""
    return {"status": "RESET_COMPLETE", "total_memories": 0}


@app.get("/api/memories")
def get_memories(request: Request, source: Optional[str] = None, limit: int = 50):
    client_host = request.client.host if request.client else ""
    if client_host not in ("127.0.0.1", "::1", "localhost"):
        return JSONResponse(status_code=403, content={"error": "Memories dashboard restricted to local Windows Studio"})
    with closing(open_db(sqlite3.Row)) as conn:
        c = conn.cursor()
        total_count = c.execute("SELECT COUNT(*) FROM memories").fetchone()[0]
        if source:
            rows = c.execute(
                "SELECT * FROM memories WHERE source_type = ? ORDER BY timestamp DESC LIMIT ?",
                (source, limit)
            ).fetchall()
        else:
            rows = c.execute(
                "SELECT * FROM memories ORDER BY timestamp DESC LIMIT ?",
                (limit,)
            ).fetchall()
        data = [dict(r) for r in rows]
    return {"count": total_count, "returned": len(data), "memories": data}


@app.get("/api/graph")
def get_knowledge_graph(request: Request):
    client_host = request.client.host if request.client else ""
    if client_host not in ("127.0.0.1", "::1", "localhost"):
        return JSONResponse(status_code=403, content={"error": "Knowledge Graph restricted to local Windows Studio"})
    with closing(open_db(sqlite3.Row)) as conn:
        c = conn.cursor()
        nodes = [dict(r) for r in c.execute("SELECT * FROM graph_nodes ORDER BY mention_count DESC LIMIT 50").fetchall()]
        edges = [dict(r) for r in c.execute("SELECT * FROM graph_edges ORDER BY timestamp DESC LIMIT 50").fetchall()]
    return {"nodes": nodes, "edges": edges}


@app.get("/api/state")
def get_user_state():
    with closing(open_db(sqlite3.Row)) as conn:
        c = conn.cursor()
        rows = c.execute("SELECT * FROM user_state").fetchall()
        total_memories = c.execute("SELECT COUNT(*) FROM memories").fetchone()[0]
        total_tokens = c.execute("SELECT COUNT(*) FROM learned_tokens").fetchone()[0]
        state = {r["key"]: {"value": r["value"], "updated_at": r["updated_at"]} for r in rows}
    return {
        "total_memories": total_memories,
        "learned_vocab_size": total_tokens,
        "user_state": state
    }


@app.get("/api/updates")
def get_updates(installed_version: Optional[str] = Query(default=None)):
    with closing(open_db(sqlite3.Row)) as conn:
        rows = conn.execute("SELECT * FROM app_updates ORDER BY released_at DESC").fetchall()

    updates_list = []
    for r in rows:
        item = dict(r)
        item["released_at_display"] = datetime.fromtimestamp(item["released_at"] / 1000.0).strftime("%d %b %Y, %I:%M %p")
        updates_list.append(item)

    latest = updates_list[0] if updates_list else None
    has_update = False
    if latest and installed_version:
        has_update = parse_version_tuple(latest["version"]) > parse_version_tuple(installed_version)

    return {
        "installed_version": installed_version or BASE_VERSION,
        "latest_version": latest["version"] if latest else BASE_VERSION,
        "has_update": has_update,
        "latest_update": latest,
        "updates": updates_list
    }


@app.get("/api/updates/bundle/{version}")
def download_update_bundle(version: str):
    with closing(open_db(sqlite3.Row)) as conn:
        row = conn.execute("SELECT * FROM app_updates WHERE version = ?", (version,)).fetchone()
    if not row:
        return JSONResponse(status_code=404, content={"error": "Update version not found"})
    bundle = dict(row)
    bundle["bundle_applied_at"] = int(time.time() * 1000)
    bundle["ui_entrypoint"] = f"/?v={version}"
    return bundle


@app.post("/api/updates/publish")
def publish_manual_update(req: PublishUpdateRequest):
    with closing(open_db(sqlite3.Row)) as conn:
        c = conn.cursor()
        rows = c.execute("SELECT version FROM app_updates ORDER BY released_at DESC").fetchall()
        existing = {r["version"] for r in rows}
        if req.version and req.version.strip():
            next_ver = req.version.strip().lstrip("v")
        else:
            latest_ver = rows[0]["version"] if rows else BASE_VERSION
            next_ver = bump_patch_version(latest_ver)
        while next_ver in existing:
            next_ver = bump_patch_version(next_ver)

        now_ms = int(time.time() * 1000)
        code_hash = compute_server_hash()
        c.execute(
            "INSERT INTO app_updates (version, title, changelog, released_at, estimated_seconds, code_hash) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (next_ver, req.title.strip(), req.changelog.strip(), now_ms, max(5, req.estimated_seconds), code_hash)
        )
        conn.commit()
    return {
        "status": "PUBLISHED",
        "version": next_ver,
        "released_at": now_ms,
        "released_at_display": datetime.fromtimestamp(now_ms / 1000.0).strftime("%d %b %Y, %I:%M %p")
    }


def _escape_like(term: str) -> str:
    return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


@app.post("/api/chat")
def chat_with_luna(req: ChatRequest):
    """
    Zero-Prebuilt-Knowledge Inference Endpoint.
    Returns ONLY patterns, entities, or episodic memories genuinely learned from user's synced device data.
    Never returns canned greetings or scripted chit-chat when unlearned.
    """
    query = req.query.strip()
    tokens = CognitiveEngine.tokenize(query)
    has_word_token = any(any(ch.isalnum() for ch in tok) for tok in tokens)

    with closing(open_db(sqlite3.Row)) as conn:
        c = conn.cursor()

        total_memories = c.execute("SELECT COUNT(*) FROM memories").fetchone()[0]
        total_vocab = c.execute("SELECT COUNT(*) FROM learned_tokens").fetchone()[0]

        # 1. Pure Blank-Brain State (0 user memories synced yet)
        if total_memories == 0:
            return {
                "query": query,
                "learned": False,
                "memories_total": 0,
                "response": (
                    f"⭕ [Untrained • 0 Memories]\n"
                    f"\"{query}\" பற்றி எனக்கு எதுவும் தெரியாது — இன்னும் எதுவும் கற்கவில்லை.\n"
                    f"(Learning Permission-ல் sensors ON செய்து தரவுகள் Sync செய்த பிறகே பதில் சொல்ல முடியும்.)"
                )
            }

        if not has_word_token:
            return {
                "query": query,
                "learned": False,
                "memories_total": total_memories,
                "response": (
                    f"⚠️ [No Learned Match • Checked {total_memories} memories / {total_vocab} tokens]\n"
                    f"\"{query}\" — ஒரு வார்த்தை அல்லது தலைப்பைப் பயன்படுத்தி கேட்கவும்."
                )
            }

        # 2. Match strictly against real learned memories & knowledge graph (escaped LIKE)
        scored_memories = {}

        # Exact phrase substring search
        safe_query = _escape_like(query)
        phrase_rows = c.execute(
            "SELECT id, source_type, text_content, timestamp FROM memories "
            "WHERE text_content LIKE ? ESCAPE '\\' ORDER BY timestamp DESC LIMIT 10",
            (f"%{safe_query}%",)
        ).fetchall()
        for r in phrase_rows:
            scored_memories[r["id"]] = {"row": dict(r), "score": 10}

        # Token-level recall
        for tok in tokens:
            safe_tok = _escape_like(tok)
            t_rows = c.execute(
                "SELECT id, source_type, text_content, timestamp FROM memories "
                "WHERE text_content LIKE ? ESCAPE '\\' ORDER BY timestamp DESC LIMIT 10",
                (f"%{safe_tok}%",)
            ).fetchall()
            for r in t_rows:
                if r["id"] in scored_memories:
                    scored_memories[r["id"]]["score"] += 2
                else:
                    scored_memories[r["id"]] = {"row": dict(r), "score": 1}

        matched_nodes = []
        for tok in tokens:
            safe_tok = _escape_like(tok)
            n_rows = c.execute(
                "SELECT label, entity_type, mention_count FROM graph_nodes WHERE LOWER(label) LIKE ? ESCAPE '\\' LIMIT 5",
                (f"%{safe_tok}%",)
            ).fetchall()
            for nr in n_rows:
                matched_nodes.append(dict(nr))

    ranked = sorted(scored_memories.values(), key=lambda x: (x["score"], x["row"]["timestamp"]), reverse=True)[:5]

    if not ranked and not matched_nodes:
        return {
            "query": query,
            "learned": False,
            "memories_total": total_memories,
            "response": (
                f"⚠️ [No Learned Match • Checked {total_memories} memories / {total_vocab} tokens]\n"
                f"\"{query}\" தொடர்பான எந்தத் தரவும் உங்கள் நினைவுகளில் இல்லை."
            )
        }

    lines = [f"🧠 [Learned Memory Recall • {total_memories} total memories]"]
    if matched_nodes:
        entities_str = ", ".join(f"{n['label']} ({n['entity_type']} ×{n['mention_count']})" for n in matched_nodes[:4])
        lines.append(f"Graph Entities: {entities_str}")
    for item in ranked:
        r = item["row"]
        ts_str = datetime.fromtimestamp(r["timestamp"] / 1000.0).strftime("%d %b %I:%M %p")
        lines.append(f"• [{r['source_type']} • {ts_str}] {r['text_content']}")

    return {
        "query": query,
        "learned": True,
        "memories_total": total_memories,
        "response": "\n".join(lines)
    }


# ─── Minimal Empty App Chat UI (For Shadow Android App WebView) ───
APP_CHAT_HTML = '''<!DOCTYPE html>
<html lang="ta">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <meta name="color-scheme" content="dark light">
    <title>Shadow • Luna</title>
    <style>
        :root {
            color-scheme: dark;
            --bg-base: #07070c;
            --bg-surface: #11111b;
            --bg-elevated: #191928;
            --border: rgba(255, 255, 255, 0.08);
            --primary: #9d4edd;
            --cyan: #00f0ff;
            --text-main: #f4f4fb;
            --text-muted: #7f809e;
            --target-size: 24px;
            --scrollbar-track: #07070c;
            --scrollbar-thumb: #2d2d44;
            scrollbar-color: var(--scrollbar-thumb) var(--scrollbar-track);
        }

        @media (pointer: coarse) {
            :root { --target-size: 44px; }
        }

        @supports not (scrollbar-color: auto) {
            * { scrollbar-width: auto; }
            ::-webkit-scrollbar { width: 6px; height: 6px; }
            ::-webkit-scrollbar-track { background: var(--scrollbar-track); }
            ::-webkit-scrollbar-thumb { background: var(--scrollbar-thumb); border-radius: 999px; }
        }

        *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

        html {
            scroll-padding-bottom: calc(5.5rem + env(safe-area-inset-bottom, 0px));
        }

        body {
            background: radial-gradient(circle at 50% 18%, rgba(157, 78, 221, 0.09), transparent 55%), var(--bg-base);
            color: var(--text-main);
            font-family: system-ui, -apple-system, 'Segoe UI', 'Noto Sans Tamil', sans-serif;
            min-height: 100dvh;
            height: 100dvh;
            display: flex;
            flex-direction: column;
            overflow-wrap: break-word;
            line-height: 1.5;
        }

        p, li { text-wrap: pretty; }
        :where(a, code) { overflow-wrap: anywhere; }
        :where(button, input, textarea) { min-block-size: var(--target-size); }

        .chat-viewport {
            flex: 1;
            min-height: 0;
            overflow-y: auto;
            padding: 16px max(16px, env(safe-area-inset-left, 16px)) 16px max(16px, env(safe-area-inset-right, 16px));
            display: flex;
            flex-direction: column;
            gap: 12px;
            max-width: 820px;
            width: 100%;
            margin: 0 auto;
        }

        .msg {
            padding: 12px 16px;
            border-radius: 18px;
            font-size: 0.94rem;
            max-width: 86%;
            white-space: pre-wrap;
            animation: fadeInUp 0.18s ease-out;
        }

        @keyframes fadeInUp {
            from { opacity: 0; transform: translateY(6px); }
            to { opacity: 1; transform: translateY(0); }
        }

        .msg.user {
            background: linear-gradient(135deg, #7b2cbf, #9d4edd);
            color: #ffffff;
            align-self: flex-end;
            border-bottom-right-radius: 4px;
            box-shadow: 0 4px 16px rgba(157, 78, 221, 0.25);
        }

        .msg.luna {
            background: var(--bg-surface);
            border: 1px solid var(--border);
            color: var(--text-main);
            align-self: flex-start;
            border-bottom-left-radius: 4px;
        }

        .msg.luna.unlearned {
            border-color: rgba(255, 255, 255, 0.09);
            color: #c2c3da;
        }

        .msg.luna.learned {
            border-color: rgba(0, 240, 255, 0.28);
        }

        .composer-shell {
            padding: 10px max(14px, env(safe-area-inset-right, 14px)) max(12px, env(safe-area-inset-bottom, 12px)) max(14px, env(safe-area-inset-left, 14px));
            background: linear-gradient(to top, var(--bg-base) 70%, transparent);
        }

        .composer-box {
            max-width: 820px;
            margin: 0 auto;
            display: flex;
            align-items: center;
            gap: 8px;
            background: var(--bg-surface);
            border: 1px solid var(--border);
            border-radius: 26px;
            padding: 5px 6px 5px 16px;
            box-shadow: 0 8px 28px rgba(0, 0, 0, 0.45);
        }

        .composer-box:focus-within {
            border-color: rgba(0, 240, 255, 0.45);
        }

        .chat-input {
            flex: 1;
            min-inline-size: 0;
            background: transparent;
            border: none;
            color: var(--text-main);
            font-size: 0.96rem;
            outline: none;
        }

        .chat-input::placeholder {
            color: var(--text-muted);
        }

        .send-btn {
            background: linear-gradient(135deg, var(--primary), var(--cyan));
            color: #07070c;
            border: none;
            border-radius: 999px;
            padding: 0 18px;
            font-weight: 700;
            font-size: 0.88rem;
            cursor: pointer;
            flex-shrink: 0;
        }

        .send-btn:active {
            transform: scale(0.97);
        }
    </style>
</head>
<body>
    <!-- Intentionally empty initial chat canvas: zero pre-built greetings or canned memories -->
    <main class="chat-viewport" id="chat-messages" aria-live="polite"></main>

    <footer class="composer-shell">
        <div class="composer-box">
            <input
                type="text"
                id="chat-input"
                class="chat-input"
                autocomplete="off"
                placeholder="Type here to test what Luna learned..."
                onkeydown="if(event.key==='Enter') sendChat()"
            >
            <button class="send-btn" onclick="sendChat()" aria-label="Send message">Send</button>
        </div>
    </footer>

    <script>
        function appendMessage(role, text, extraClass) {
            const container = document.getElementById('chat-messages');
            const bubble = document.createElement('div');
            bubble.className = 'msg ' + role + (extraClass ? ' ' + extraClass : '');
            bubble.textContent = text;
            container.appendChild(bubble);
            container.scrollTop = container.scrollHeight;
        }

        async function sendChat() {
            const input = document.getElementById('chat-input');
            const q = input.value.trim();
            if (!q) return;
            input.value = '';

            appendMessage('user', q);

            try {
                const res = await fetch('/api/chat', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({query: q})
                });
                const data = await res.json();
                appendMessage('luna', data.response || '', data.learned ? 'learned' : 'unlearned');
            } catch (e) {
                appendMessage('luna', 'Luna Server offline. Check Wi-Fi connection.', 'unlearned');
            }
        }
    </script>
</body>
</html>'''

def _html_no_cache(content: str) -> HTMLResponse:
    return HTMLResponse(
        content=content,
        headers={
            "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
            "Pragma": "no-cache",
        },
    )


@app.get("/")
def dashboard(request: Request, v: Optional[str] = None, mode: Optional[str] = None):
    ua = request.headers.get("user-agent", "")
    xrw = request.headers.get("x-requested-with", "")
    is_android_app = (
        mode == "app"
        or v is not None
        or "com.personal.devicememory" in xrw
        or "; wv)" in ua
    )
    if is_android_app:
        return _html_no_cache(APP_CHAT_HTML)
    return JSONResponse(
        status_code=403,
        content={
            "status": "PRIVATE",
            "message": "Web Dashboard disabled (Option A). Open 'Shadow Luna Server' Windows App on PC or Shadow App on Mobile.",
        },
    )


@app.get("/app", response_class=HTMLResponse)
def mobile_app_ui():
    return _html_no_cache(APP_CHAT_HTML)


if __name__ == "__main__":
    import uvicorn
    print("🌙 Starting Luna Cognitive Core (Headless API + Mobile App UI) on http://0.0.0.0:8000 ...")
    uvicorn.run("luna_server:app", host="0.0.0.0", port=8000, reload=True)
