import hashlib
import ipaddress
import json
import math
import os
import re
import socket
import sqlite3
import threading
import time
import unicodedata
import urllib.error
import urllib.request
from contextlib import closing
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional
from fastapi import Body, FastAPI, Query, Request
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

import luna_sync_crypto

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


def _is_local_host(client_host: Optional[str]) -> bool:
    if not client_host:
        return False
    h = client_host.strip().lower()
    if h in ("127.0.0.1", "::1", "::ffff:127.0.0.1", "localhost"):
        return True
    try:
        addr = ipaddress.ip_address(h)
        return addr.is_loopback
    except ValueError:
        return False


def _fmt_ts(ts_ms: int, fmt: str) -> str:
    try:
        sec = float(ts_ms) / 1000.0
        # Guard bounds for Windows CPython localtime [1970..2999]
        if sec < 0.0:
            sec = 0.0
        elif sec > 32503680000.0:
            sec = 32503680000.0
        return datetime.fromtimestamp(sec).strftime(fmt)
    except Exception:
        return "Recent"


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
        # 8. STDP Sequential Causal Transitions (learns "what follows what" strictly from synced data)
        c.execute('''
            CREATE TABLE IF NOT EXISTS learned_transitions (
                transition_key TEXT PRIMARY KEY,
                from_concept TEXT NOT NULL,
                to_concept TEXT NOT NULL,
                weight REAL DEFAULT 1.0,
                transition_count INTEGER DEFAULT 1,
                last_seen INTEGER DEFAULT 0
            )
        ''')
        c.execute("CREATE INDEX IF NOT EXISTS idx_transitions_from ON learned_transitions(from_concept)")
        c.execute("CREATE INDEX IF NOT EXISTS idx_memories_ts ON memories(timestamp DESC)")
        c.execute("CREATE INDEX IF NOT EXISTS idx_memories_src_ts ON memories(source_type, timestamp DESC)")
        c.execute("CREATE INDEX IF NOT EXISTS idx_nodes_mentions ON graph_nodes(mention_count DESC)")
        c.execute("CREATE INDEX IF NOT EXISTS idx_edges_lookup ON graph_edges(source_id, target_id, relation)")
        c.execute("CREATE INDEX IF NOT EXISTS idx_edges_ts ON graph_edges(timestamp DESC)")
        c.execute("CREATE INDEX IF NOT EXISTS idx_tokens_freq_seen ON learned_tokens(frequency DESC, last_seen DESC)")
        c.execute("CREATE INDEX IF NOT EXISTS idx_updates_rel ON app_updates(released_at DESC)")
        # 9. Synced 32D Neural Vectors from On-Device Titans + Matryoshka Trainer
        c.execute('''
            CREATE TABLE IF NOT EXISTS neural_vectors (
                concept TEXT PRIMARY KEY,
                vector_csv TEXT NOT NULL,
                fisher_importance REAL DEFAULT 1.0,
                surprise_score REAL DEFAULT 0.2,
                updated_at INTEGER DEFAULT 0
            )
        ''')
        # 10. Synced Cross-Modal Spatio-Temporal Episodes ("Life Moments")
        c.execute('''
            CREATE TABLE IF NOT EXISTS episodes (
                episode_id TEXT PRIMARY KEY,
                title_label TEXT NOT NULL,
                start_millis INTEGER NOT NULL,
                end_millis INTEGER NOT NULL,
                primary_place TEXT DEFAULT '',
                dominant_sources TEXT DEFAULT '',
                key_concepts TEXT DEFAULT '',
                record_count INTEGER DEFAULT 1,
                narrative_summary TEXT NOT NULL,
                updated_at INTEGER DEFAULT 0
            )
        ''')
        c.execute("CREATE INDEX IF NOT EXISTS idx_episodes_end ON episodes(end_millis DESC)")
        c.execute("CREATE INDEX IF NOT EXISTS idx_vectors_imp ON neural_vectors(fisher_importance DESC)")
        conn.commit()


handshake_lock = threading.Lock()
_e2ee_laptop_priv, _e2ee_laptop_pub = luna_sync_crypto.generate_ecdh_keypair()
_e2ee_salt = hashlib.sha256(str(time.time()).encode("utf-8")).hexdigest()[:16]
_e2ee_peer_keys: Dict[str, tuple[bytes, bytes]] = {}

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
    "last_activity_at": 0,
    "laptop_pubkey": _e2ee_laptop_pub,
    "session_salt": _e2ee_salt,
}


def _refresh_handshake_locked(now_ms: int):
    # Auto-expire waiting or hung transferring sessions after 120s so UI never deadlocks
    phase = handshake_state["phase"]
    if phase in ("WAITING_FOR_MOBILE", "WAITING_FOR_LAPTOP"):
        if handshake_state["laptop_ready"] and now_ms - handshake_state["laptop_ready_at"] > 120_000:
            handshake_state["laptop_ready"] = False
        if handshake_state["mobile_ready"] and now_ms - handshake_state["mobile_ready_at"] > 120_000:
            handshake_state["mobile_ready"] = False
        if not handshake_state["laptop_ready"] and not handshake_state["mobile_ready"]:
            handshake_state["phase"] = "IDLE"
            handshake_state["last_message"] = ""
    elif phase == "TRANSFERRING":
        last_act = max(
            handshake_state.get("last_activity_at", 0),
            handshake_state.get("laptop_ready_at", 0),
            handshake_state.get("mobile_ready_at", 0),
        )
        if last_act > 0 and now_ms - last_act > 120_000:
            handshake_state["laptop_ready"] = False
            handshake_state["mobile_ready"] = False
            handshake_state["phase"] = "IDLE"
            handshake_state["last_message"] = "Transfer timed out — tap Connect to retry"
    elif phase == "COMPLETED":
        if not handshake_state["laptop_ready"] and not handshake_state["mobile_ready"]:
            if handshake_state.get("completed_at", 0) > 0 and now_ms - handshake_state["completed_at"] > 15_000:
                handshake_state["phase"] = "IDLE"


def _sort_updates_semantic(rows):
    return sorted(rows, key=lambda r: (parse_version_tuple(r["version"]), r["released_at"]), reverse=True)


def register_server_release():
    code_hash = compute_server_hash()
    now_ms = int(time.time() * 1000)
    with closing(open_db(sqlite3.Row)) as conn:
        c = conn.cursor()

        rows = _sort_updates_semantic(c.execute("SELECT * FROM app_updates").fetchall())
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
        norm = unicodedata.normalize("NFC", text.lower())
        # Preserve Tamil vowel signs/combining marks cleanly alongside Latin tokens
        tokens = re.findall(r"[\w\u0B80-\u0BFF]+", norm, flags=re.UNICODE)
        return [t for t in tokens if len(t) >= 2 and any(ch.isalnum() for ch in t)]

    @staticmethod
    def learn_from_record(record: dict, conn: sqlite3.Connection):
        c = conn.cursor()
        source = record.get("source_type", "")
        text = record.get("text_content") or ""
        ts = record.get("timestamp", int(time.time() * 1000))
        try:
            parsed_meta = json.loads(record.get("metadata_json") or "{}")
            meta = parsed_meta if isinstance(parsed_meta, dict) else {}
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
        pkg_raw = meta.get("package") or meta.get("package_name")
        pkg = pkg_raw.strip() if isinstance(pkg_raw, str) and pkg_raw.strip() else None
        if pkg:
            app_name = pkg.split(".")[-1].capitalize() or pkg
            c.execute(
                "INSERT INTO graph_nodes (id, label, entity_type, last_seen) VALUES (?, ?, ?, ?) "
                "ON CONFLICT(id) DO UPDATE SET mention_count = mention_count + 1, last_seen = ?",
                (f"app:{pkg}", app_name, "APP", ts, ts)
            )

        # 3. Extract Person node from Notifications / Contacts
        title_raw = meta.get("title") or meta.get("display_name")
        title = title_raw.strip() if isinstance(title_raw, str) and title_raw.strip() else None
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

        # 4. Extract Media nodes (Photos / Screenshots / 2026 Video V-JEPA + Audio Spectral + OCR + Speaker Clustering)
        clean_text = text.strip()
        if source == "PHOTO" and clean_text:
            is_screenshot = bool(meta.get("is_screenshot", False))
            node_type = "SCREENSHOT" if is_screenshot else "PHOTO"
            c.execute(
                "INSERT INTO graph_nodes (id, label, entity_type, last_seen) VALUES (?, ?, ?, ?) "
                "ON CONFLICT(id) DO UPDATE SET mention_count = mention_count + 1, last_seen = ?",
                (f"media:{record['id']}", clean_text[:80], node_type, ts, ts)
            )
            va = meta.get("visual_analysis")
            if isinstance(va, dict):
                for tag in (va.get("lighting_scene"), va.get("color_tone"), va.get("visual_code"), "Document/Text" if va.get("is_document") else None):
                    if isinstance(tag, str):
                        ct = tag.strip()
                        if ct and ct != "Neutral":
                            c.execute(
                                "INSERT INTO graph_nodes (id, label, entity_type, last_seen) VALUES (?, ?, ?, ?) "
                                "ON CONFLICT(id) DO UPDATE SET mention_count = mention_count + 1, last_seen = ?",
                                (f"av_scene:{ct.lower()}", ct, "AV_SCENE", ts, ts)
                            )
        elif source in ("VIDEO", "AUDIO"):
            if clean_text:
                c.execute(
                    "INSERT INTO graph_nodes (id, label, entity_type, last_seen) VALUES (?, ?, ?, ?) "
                    "ON CONFLICT(id) DO UPDATE SET mention_count = mention_count + 1, last_seen = ?",
                    (f"media:{record['id']}", clean_text[:90], source, ts, ts)
                )
            av = meta.get("av_analysis") if source == "VIDEO" else meta.get("audio_analysis")
            if isinstance(av, dict):
                av_tags = [
                    av.get("motion_scene"),
                    av.get("lighting_scene"),
                    av.get("color_tone"),
                    av.get("codec"),
                    av.get("audio_scene"),
                    av.get("acoustic_scene"),
                    av.get("visual_code"),
                    av.get("acoustic_code"),
                    av.get("speaker_cluster"),
                ]
                for tag in av_tags:
                    if isinstance(tag, str):
                        clean_tag = tag.strip()
                        if clean_tag and clean_tag != "Neutral":
                            c.execute(
                                "INSERT INTO graph_nodes (id, label, entity_type, last_seen) VALUES (?, ?, ?, ?) "
                                "ON CONFLICT(id) DO UPDATE SET mention_count = mention_count + 1, last_seen = ?",
                                (f"av_scene:{clean_tag.lower()}", clean_tag, "AV_SCENE", ts, ts)
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

    @staticmethod
    def primary_concept(record: dict) -> Optional[str]:
        text = record.get("text_content") or ""
        toks = CognitiveEngine.tokenize(text)
        stop = {"com", "sec", "android", "app", "photo", "video", "audio", "screenshot", "sending", "file", "from", "with"}
        for t in toks:
            if len(t) >= 3 and t not in stop:
                return t
        try:
            parsed_meta = json.loads(record.get("metadata_json") or "{}")
            meta = parsed_meta if isinstance(parsed_meta, dict) else {}
            pkg = meta.get("package")
            if isinstance(pkg, str) and pkg.strip():
                return pkg.strip().split(".")[-1].lower()
        except Exception:
            pass
        return toks[0] if toks else None

    @staticmethod
    def learn_sequence_transitions(records_sorted: List[dict], conn: sqlite3.Connection):
        import math
        c = conn.cursor()
        for i in range(len(records_sorted) - 1):
            r_a, r_b = records_sorted[i], records_sorted[i + 1]
            dt = r_b["timestamp"] - r_a["timestamp"]
            if dt < 0 or dt > 1_800_000:
                continue
            ca = CognitiveEngine.primary_concept(r_a)
            cb = CognitiveEngine.primary_concept(r_b)
            if not ca or not cb or ca == cb:
                continue
            stdp_gain = max(0.15, min(0.95, 0.95 * math.exp(-(dt / 60_000.0) / 12.0)))
            tkey = f"{ca}->{cb}"
            c.execute(
                "INSERT INTO learned_transitions (transition_key, from_concept, to_concept, weight, transition_count, last_seen) "
                "VALUES (?, ?, ?, ?, 1, ?) ON CONFLICT(transition_key) DO UPDATE SET "
                "weight = MIN(100.0, weight * 0.94 + ?), transition_count = transition_count + 1, last_seen = MAX(last_seen, ?)",
                (tkey, ca, cb, stdp_gain, r_b["timestamp"], stdp_gain, r_b["timestamp"])
            )

    @staticmethod
    def levenshtein(a: str, b: str) -> int:
        if a == b:
            return 0
        if not a:
            return len(b)
        if not b:
            return len(a)
        prev = list(range(len(b) + 1))
        for i, ca in enumerate(a, 1):
            curr = [i]
            for j, cb in enumerate(b, 1):
                curr.append(min(curr[-1] + 1, prev[j] + 1, prev[j - 1] + (0 if ca == cb else 1)))
            prev = curr
        return prev[-1]

    @staticmethod
    def trigram_similarity(a: str, b: str) -> float:
        if a == b:
            return 1.0
        if len(a) < 3 or len(b) < 3:
            return 0.7 if (max(len(a), len(b)) <= 3 and (a in b or b in a)) else 0.0
        ta = {a[i:i + 3] for i in range(len(a) - 2)}
        tb = {b[i:i + 3] for i in range(len(b) - 2)}
        inter = len(ta & tb)
        union = len(ta | tb)
        return (inter / union) if union > 0 else 0.0


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


class SyncVector(BaseModel):
    concept: str
    vector_csv: str
    fisher_importance: float = 1.0
    surprise_score: float = 0.2


class SyncTransition(BaseModel):
    from_concept: str
    to_concept: str
    stdp_weight: float = 1.0
    transition_count: int = 1


class SyncEpisode(BaseModel):
    episode_id: str
    title_label: str
    start_millis: int
    end_millis: int
    primary_place: str = ""
    dominant_sources: str = ""
    key_concepts: str = ""
    record_count: int = 1
    narrative_summary: str


class SyncPayload(BaseModel):
    client_version: Optional[str] = "0.8.0"
    records: List[SyncRecord] = []
    tokens: List[SyncToken] = []
    vectors: List[SyncVector] = []
    transitions: List[SyncTransition] = []
    episodes: List[SyncEpisode] = []


class HandshakeMobileReadyRequest(BaseModel):
    mobile_pubkey: Optional[str] = None


class FeedbackRequest(BaseModel):
    anchor_concept: Optional[str] = None
    is_positive: bool = True


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


_BILINGUAL_GROUPS = [
    ("photo", "photos", "camera", "picture", "image", "படம்", "புகைப்படம்", "கேமரா"),
    ("video", "clip", "recording", "வீடியோ", "காணொளி"),
    ("audio", "voice", "music", "song", "sound", "ஆடியோ", "குரல்", "பாடல்", "இசை"),
    ("food", "lunch", "dinner", "breakfast", "restaurant", "swiggy", "zomato", "உணவு", "சாப்பாடு", "டிபன்"),
    ("payment", "paid", "money", "bill", "receipt", "bank", "upi", "gpay", "paytm", "பணம்", "கட்டணம்", "ரசீது"),
    ("hospital", "doctor", "clinic", "medicine", "pharmacy", "மருத்துவமனை", "டாக்டர்", "மருந்து"),
    ("travel", "trip", "flight", "train", "bus", "cab", "uber", "ola", "பயணம்", "ரயில்", "பஸ்"),
    ("meeting", "call", "office", "work", "zoom", "மீட்டிங்", "சந்திப்பு", "வேலை"),
    ("college", "school", "class", "exam", "study", "notes", "கல்லூரி", "பள்ளி", "வகுப்பு"),
    ("mom", "mother", "amma", "அம்மா"),
    ("dad", "father", "appa", "அப்பா"),
    ("friend", "friends", "buddy", "நண்பர்", "நண்பர்கள்"),
    ("home", "house", "வீடு"),
    ("shopping", "order", "amazon", "flipkart", "delivery", "ஆர்டர்", "பொருள்"),
    ("message", "chat", "whatsapp", "telegram", "sms", "செய்தி", "மெசேஜ்"),
    ("outdoor", "bright", "park", "beach", "வெளியே", "பூங்கா", "கடற்கரை"),
]
_BILINGUAL_MAP = {w: grp for grp in _BILINGUAL_GROUPS for w in grp}


def _has_tamil(word: str) -> bool:
    return any("\u0b80" <= ch <= "\u0bff" for ch in word)


def _expand_bilingual_synonyms(terms: List[str]) -> List[str]:
    base = set(terms)
    out = []
    seen = set()
    for t in terms:
        grp = _BILINGUAL_MAP.get(t)
        if not grp:
            continue
        t_tamil = _has_tamil(t)
        ordered = sorted(grp, key=lambda alt: 0 if (_has_tamil(alt) != t_tamil) else 1)
        cnt = 0
        for alt in ordered:
            if alt not in base and alt not in seen:
                seen.add(alt)
                out.append(alt)
                cnt += 1
                if cnt >= 10:
                    break
    return out


def _parse_vec_csv(csv_str: str) -> List[float]:
    try:
        return [float(x) for x in (csv_str or "").split(",") if x.strip()]
    except Exception:
        return []


def _cosine(a: List[float], b: List[float]) -> float:
    n = min(len(a), len(b))
    if n == 0:
        return 0.0
    dot = sum(a[i] * b[i] for i in range(n))
    na = math.sqrt(sum(a[i] * a[i] for i in range(n)))
    nb = math.sqrt(sum(b[i] * b[i] for i in range(n)))
    if na < 1e-8 or nb < 1e-8:
        return 0.0
    return max(-1.0, min(1.0, dot / (na * nb)))


def _try_local_slm_summary(query: str, evidence_lines: List[str]) -> Optional[str]:
    """Strictly grounded optional localhost Ollama SLM hook (100% offline, never invents facts)."""
    if not evidence_lines or os.environ.get("SHADOW_ENABLE_LOCAL_SLM", "0") != "1":
        return None
    try:
        with urllib.request.urlopen("http://127.0.0.1:11434/api/tags", timeout=0.3) as resp:
            tags_data = json.loads(resp.read().decode("utf-8"))
        models = [m.get("name") for m in tags_data.get("models", []) if m.get("name")]
        if not models:
            return None
        model = os.environ.get("SHADOW_LOCAL_SLM_MODEL") or models[0]
        prompt = (
            "You are Shadow Luna, a strictly private personal memory assistant. "
            "Summarize the answer ONLY from the following user records concisely in Tamil or English matching the question. "
            "Do not invent anything outside these lines.\n\nQuestion: " + query + "\nRecords:\n" + "\n".join(evidence_lines[:5])
        )
        req = urllib.request.Request(
            "http://127.0.0.1:11434/api/generate",
            method="POST",
            data=json.dumps({"model": model, "prompt": prompt, "stream": False}).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=2.5) as resp:
            out = json.loads(resp.read().decode("utf-8")).get("response", "").strip()
            return out or None
    except Exception:
        return None


DISCOVERY_PORT = 8800


def _udp_discovery_loop():
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        else:
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
    if not _is_local_host(client_host):
        return JSONResponse(status_code=403, content={"error": "Laptop handshake must originate locally"})
    now_ms = int(time.time() * 1000)
    with handshake_lock:
        _refresh_handshake_locked(now_ms)
        handshake_state["laptop_ready"] = True
        handshake_state["laptop_ready_at"] = now_ms
        handshake_state["last_activity_at"] = now_ms
        if handshake_state["phase"] == "TRANSFERRING":
            return dict(handshake_state)
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
def handshake_mobile_ready(req: Optional[HandshakeMobileReadyRequest] = Body(default=None)):
    now_ms = int(time.time() * 1000)
    with handshake_lock:
        _refresh_handshake_locked(now_ms)
        if req and req.mobile_pubkey:
            try:
                sec = luna_sync_crypto.compute_shared_secret(_e2ee_laptop_priv, req.mobile_pubkey)
                _e2ee_peer_keys[req.mobile_pubkey] = luna_sync_crypto.derive_session_keys(sec, handshake_state["session_salt"])
            except Exception:
                pass
        handshake_state["mobile_ready"] = True
        handshake_state["mobile_ready_at"] = now_ms
        handshake_state["last_activity_at"] = now_ms
        if handshake_state["phase"] == "TRANSFERRING":
            return dict(handshake_state)
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
    now_ms = int(time.time() * 1000)
    with handshake_lock:
        handshake_state["phase"] = "TRANSFERRING"
        handshake_state["last_activity_at"] = now_ms
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
    with handshake_lock:
        with closing(open_db()) as conn:
            c = conn.cursor()
            c.execute(
                "INSERT INTO sync_sessions (synced_at, records_added, tokens_added, summary) VALUES (?, ?, ?, ?)",
                (now_ms, req.received_records, req.received_tokens, summary)
            )
            conn.commit()
        handshake_state["laptop_ready"] = False
        handshake_state["mobile_ready"] = False
        handshake_state["phase"] = "COMPLETED"
        handshake_state["total_records"] = req.total_records
        handshake_state["received_records"] = req.received_records
        handshake_state["received_tokens"] = req.received_tokens
        handshake_state["completed_at"] = now_ms
        handshake_state["last_activity_at"] = now_ms
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
def sync_memories(raw_body: Dict[str, Any] = Body(...)):
    e2ee_used = False
    if "encrypted_payload" in raw_body and "mobile_pubkey" in raw_body:
        mob_pub = raw_body.get("mobile_pubkey", "")
        with handshake_lock:
            keys = _e2ee_peer_keys.get(mob_pub)
        if not keys:
            return JSONResponse(status_code=401, content={"error": "Missing E2EE session keys — reconnect handshake"})
        try:
            plain_bytes = luna_sync_crypto.decrypt_envelope(
                raw_body["encrypted_payload"], raw_body["iv"], raw_body["hmac_tag"], keys[0], keys[1]
            )
            payload = SyncPayload.model_validate(json.loads(plain_bytes.decode("utf-8")))
            e2ee_used = True
        except Exception as e:
            return JSONResponse(status_code=400, content={"error": f"E2EE decrypt failed: {e}"})
    else:
        payload = SyncPayload.model_validate(raw_body)

    saved = 0
    tokens_saved = 0
    vectors_saved = 0
    episodes_saved = 0
    now = int(time.time() * 1000)

    with closing(open_db()) as conn:
        c = conn.cursor()
        newly_saved_records = []
        for r in payload.records:
            c.execute(
                "INSERT OR IGNORE INTO memories (id, source_type, timestamp, text_content, metadata_json, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (r.id, r.source_type, r.timestamp, r.text_content, r.metadata_json, now)
            )
            if c.rowcount > 0:
                saved += 1
                rd = r.model_dump()
                newly_saved_records.append(rd)
                CognitiveEngine.learn_from_record(rd, conn)

        if len(newly_saved_records) >= 2:
            newly_saved_records.sort(key=lambda x: x["timestamp"])
            CognitiveEngine.learn_sequence_transitions(newly_saved_records, conn)

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

        for vec in payload.vectors:
            con = vec.concept.strip().lower()
            if not con or not vec.vector_csv:
                continue
            c.execute(
                "INSERT INTO neural_vectors (concept, vector_csv, fisher_importance, surprise_score, updated_at) "
                "VALUES (?, ?, ?, ?, ?) ON CONFLICT(concept) DO UPDATE SET "
                "vector_csv = ?, fisher_importance = MAX(fisher_importance, ?), surprise_score = ?, updated_at = ?",
                (con, vec.vector_csv, vec.fisher_importance, vec.surprise_score, now,
                 vec.vector_csv, vec.fisher_importance, vec.surprise_score, now)
            )
            vectors_saved += 1

        for tr in payload.transitions:
            fa = tr.from_concept.strip().lower()
            fb = tr.to_concept.strip().lower()
            if fa and fb and fa != fb:
                c.execute(
                    "INSERT INTO learned_transitions (transition_key, from_concept, to_concept, weight, transition_count, last_seen) "
                    "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(transition_key) DO UPDATE SET "
                    "weight = MAX(weight, ?), transition_count = MAX(transition_count, ?), last_seen = MAX(last_seen, ?)",
                    (f"{fa}->{fb}", fa, fb, tr.stdp_weight, tr.transition_count, now,
                     tr.stdp_weight, tr.transition_count, now)
                )

        for ep in payload.episodes:
            c.execute(
                "INSERT INTO episodes (episode_id, title_label, start_millis, end_millis, primary_place, "
                "dominant_sources, key_concepts, record_count, narrative_summary, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(episode_id) DO UPDATE SET "
                "narrative_summary = ?, key_concepts = ?, record_count = MAX(record_count, ?), end_millis = MAX(end_millis, ?), updated_at = ?",
                (ep.episode_id, ep.title_label, ep.start_millis, ep.end_millis, ep.primary_place,
                 ep.dominant_sources, ep.key_concepts, ep.record_count, ep.narrative_summary, now,
                 ep.narrative_summary, ep.key_concepts, ep.record_count, ep.end_millis, now)
            )
            c.execute(
                "INSERT INTO graph_nodes (id, label, entity_type, last_seen) VALUES (?, ?, 'EPISODE', ?) "
                "ON CONFLICT(id) DO UPDATE SET mention_count = mention_count + 1, last_seen = ?",
                (f"episode:{ep.episode_id}", ep.title_label[:42], ep.end_millis, ep.end_millis)
            )
            episodes_saved += 1

        conn.commit()
    return {
        "status": "SUCCESS",
        "e2ee": e2ee_used,
        "synced_records": saved,
        "synced_tokens": tokens_saved,
        "synced_vectors": vectors_saved,
        "synced_episodes": episodes_saved,
        "total_received": len(payload.records)
    }


@app.post("/api/feedback")
def submit_feedback(req: FeedbackRequest):
    """Live Human-in-the-Loop RLHF feedback endpoint adjusting causal & graph weights."""
    factor = 1.15 if req.is_positive else 0.72
    anchor = (req.anchor_concept or "").strip().lower()
    with closing(open_db()) as conn:
        c = conn.cursor()
        if anchor:
            c.execute(
                "UPDATE learned_transitions SET weight = MAX(0.1, MIN(100.0, weight * ?)) WHERE from_concept = ?",
                (factor, anchor)
            )
            c.execute(
                "UPDATE graph_nodes SET mention_count = MAX(1, mention_count + ?) WHERE LOWER(label) = ?",
                (1 if req.is_positive else -1, anchor)
            )
        conn.commit()
    return {"status": "UPDATED", "anchor": anchor, "positive": req.is_positive}


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
        c.execute("DELETE FROM learned_transitions")
        c.execute("DELETE FROM neural_vectors")
        c.execute("DELETE FROM episodes")
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
    if not _is_local_host(client_host):
        return JSONResponse(status_code=403, content={"error": "Memories dashboard restricted to local Windows Studio"})
    safe_limit = max(1, min(limit, 500))
    with closing(open_db(sqlite3.Row)) as conn:
        c = conn.cursor()
        total_count = c.execute("SELECT COUNT(*) FROM memories").fetchone()[0]
        if source:
            rows = c.execute(
                "SELECT * FROM memories WHERE source_type = ? ORDER BY timestamp DESC LIMIT ?",
                (source, safe_limit)
            ).fetchall()
        else:
            rows = c.execute(
                "SELECT * FROM memories ORDER BY timestamp DESC LIMIT ?",
                (safe_limit,)
            ).fetchall()
        data = [dict(r) for r in rows]
    return {"count": total_count, "returned": len(data), "memories": data}


@app.get("/api/graph")
def get_knowledge_graph(request: Request):
    client_host = request.client.host if request.client else ""
    if not _is_local_host(client_host):
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
        rows = _sort_updates_semantic(conn.execute("SELECT * FROM app_updates").fetchall())

    updates_list = []
    for r in rows:
        item = dict(r)
        item["released_at_display"] = _fmt_ts(item["released_at"], "%d %b %Y, %I:%M %p")
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
def publish_manual_update(request: Request, req: PublishUpdateRequest):
    client_host = request.client.host if request.client else ""
    if not _is_local_host(client_host):
        return JSONResponse(status_code=403, content={"error": "Publishing updates restricted to local Windows Studio"})
    with closing(open_db(sqlite3.Row)) as conn:
        c = conn.cursor()
        rows = _sort_updates_semantic(c.execute("SELECT version, released_at FROM app_updates").fetchall())
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
        "released_at_display": _fmt_ts(now_ms, "%d %b %Y, %I:%M %p")
    }


def _escape_like(term: str) -> str:
    return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _resolve_temporal_window(query_lower: str):
    now_dt = datetime.now()
    day_start = now_dt.replace(hour=0, minute=0, second=0, microsecond=0)
    if any(w in query_lower for w in ("yesterday", "நேற்று", "நேத்து")):
        s = int((day_start - timedelta(days=1)).timestamp() * 1000)
        e = int(day_start.timestamp() * 1000) - 1
        return s, e, "Yesterday"
    if any(w in query_lower for w in ("today", "இன்று", "இன்னிக்கு")):
        s = int(day_start.timestamp() * 1000)
        e = int(now_dt.timestamp() * 1000)
        return s, e, "Today"
    if "இந்த வாரம்" in query_lower:
        week_start = day_start - timedelta(days=now_dt.weekday())
        return int(week_start.timestamp() * 1000), int(now_dt.timestamp() * 1000), "This week"
    if "last week" in query_lower:
        s = int((day_start - timedelta(days=7)).timestamp() * 1000)
        e = int(now_dt.timestamp() * 1000)
        return s, e, "Last 7 days"
    return None


@app.post("/api/chat")
def chat_with_luna(req: ChatRequest):
    """
    Zero-Prebuilt-Knowledge Inference Endpoint (CognitiveEngine v3).
    Combines BM25 specificity + recency decay + Levenshtein/3-gram fuzzy matching +
    STDP sequential causal chains + temporal window filtering strictly from synced user data.
    Never returns canned greetings or pre-seeded knowledge when unlearned.
    """
    import math
    query = req.query.strip()
    q_lower = query.lower()
    tokens = CognitiveEngine.tokenize(query)
    has_word_token = any(any(ch.isalnum() for ch in tok) for tok in tokens)

    stop_words = {
        "what", "when", "where", "which", "who", "how", "why", "about", "tell", "show",
        "did", "does", "have", "been", "from", "with", "this", "that", "your", "the",
        "and", "for", "then", "next", "after", "eppo", "enna", "yaar", "pati", "patti",
        "sollu", "kaatu", "pannen", "use", "apram", "aparam", "today", "yesterday"
    }

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

        # Summary / profile overview intent
        is_summary = len(q_lower.split()) >= 2 and any(
            k in q_lower for k in ("summary", "pattern", "habit", "learned", "profile", "overview", "ella", "kathukitta")
        )
        if is_summary:
            top_nodes = c.execute("SELECT label, entity_type, mention_count FROM graph_nodes ORDER BY mention_count DESC LIMIT 5").fetchall()
            top_toks = c.execute("SELECT token, frequency FROM learned_tokens ORDER BY frequency DESC LIMIT 6").fetchall()
            top_chains = c.execute("SELECT from_concept, to_concept, transition_count FROM learned_transitions ORDER BY weight DESC LIMIT 4").fetchall()
            lines = [f"🧠 [Cognitive Profile • {total_memories} memories • {total_vocab} tokens]"]
            if top_nodes:
                lines.append("Top Entities: " + ", ".join(f"{r['label']} (×{r['mention_count']})" for r in top_nodes))
            if top_chains:
                lines.append("STDP Causal Chains: " + " • ".join(f"{r['from_concept']} → {r['to_concept']} (×{r['transition_count']})" for r in top_chains))
            if top_toks:
                lines.append("Top Signals: " + ", ".join(f"{r['token']} (×{r['frequency']})" for r in top_toks))
            return {
                "query": query,
                "learned": True,
                "memories_total": total_memories,
                "response": "\n".join(lines)
            }

        meaningful = [
            t for t in tokens
            if t not in stop_words and t not in {"app", "photo", "video", "audio", "screenshot"}
        ] or [t for t in tokens if t not in stop_words] or tokens

        # Fuzzy Levenshtein + Trigram expansion against learned vocabulary & graph nodes
        vocab_rows = c.execute("SELECT token FROM learned_tokens ORDER BY frequency DESC LIMIT 300").fetchall()
        node_rows = c.execute("SELECT LOWER(label) AS lbl FROM graph_nodes ORDER BY mention_count DESC LIMIT 120").fetchall()
        vocab_pool = list(dict.fromkeys([r["token"] for r in vocab_rows] + [r["lbl"] for r in node_rows if r["lbl"]]))

        expanded_terms = list(meaningful)
        fuzzy_matches = []
        corrected_map = {}
        for t in meaningful:
            if len(t) < 4 or t in vocab_pool:
                continue
            max_dist = 2 if len(t) >= 6 else 1
            best_lev = None
            best_d = max_dist + 1
            for v in vocab_pool:
                if abs(len(v) - len(t)) <= max_dist:
                    d = CognitiveEngine.levenshtein(t, v)
                    if 1 <= d < best_d:
                        best_d = d
                        best_lev = v
            if best_lev:
                corrected_map[t] = best_lev
                if best_lev not in expanded_terms:
                    expanded_terms.append(best_lev)
                fuzzy_matches.append(f"{t} → {best_lev}")
            else:
                best_ng = None
                best_sim = 0.42
                for v in vocab_pool:
                    sim = CognitiveEngine.trigram_similarity(t, v)
                    if sim > best_sim:
                        best_sim = sim
                        best_ng = v
                if best_ng:
                    corrected_map[t] = best_ng
                    if best_ng not in expanded_terms:
                        expanded_terms.append(best_ng)
                    fuzzy_matches.append(f"{t} ≈ {best_ng}")

        # Bilingual Tamil <-> English linguistic bridge
        bilingual_alts = _expand_bilingual_synonyms(expanded_terms)
        for alt in bilingual_alts:
            if alt not in expanded_terms:
                expanded_terms.append(alt)

        now_ms = int(time.time() * 1000)
        scored_memories = {}

        def add_score(row_dict: dict, delta: float):
            age_days = max(0.0, (now_ms - row_dict["timestamp"]) / 86_400_000.0)
            recency = 0.78 + 0.35 * math.exp(-age_days / 14.0)
            rid = row_dict["id"]
            if rid in scored_memories:
                scored_memories[rid]["score"] += delta * recency
            else:
                scored_memories[rid] = {"row": row_dict, "score": delta * recency}

        # Exact phrase substring search
        safe_query = _escape_like(query)
        for r in c.execute(
            "SELECT id, source_type, text_content, timestamp FROM memories "
            "WHERE text_content LIKE ? ESCAPE '\\' ORDER BY timestamp DESC LIMIT 10",
            (f"%{safe_query}%",)
        ).fetchall():
            add_score(dict(r), 10.0)

        # BM25 specificity-weighted token recall
        bilingual_set = set(bilingual_alts)
        for tok in expanded_terms:
            safe_tok = _escape_like(tok)
            t_rows = c.execute(
                "SELECT id, source_type, text_content, timestamp FROM memories "
                "WHERE text_content LIKE ? ESCAPE '\\' ORDER BY timestamp DESC LIMIT 40",
                (f"%{safe_tok}%",)
            ).fetchall()
            specificity = min(2.2, max(0.8, 1.0 + math.log(1.0 + 10.0 / max(1, len(t_rows))) - 0.15 * (len(t_rows) / 40.0)))
            role_w = 4.0 if tok in meaningful else (2.1 if tok in bilingual_set else 2.4)
            base_w = role_w * specificity
            for r in t_rows:
                add_score(dict(r), base_w)

        # Dense Vector Cosine Recall over synced phone neural_vectors
        vec_neighbors = []
        vec_rows = c.execute("SELECT concept, vector_csv FROM neural_vectors ORDER BY fisher_importance DESC LIMIT 250").fetchall()
        if vec_rows:
            parsed_vecs = {vr["concept"]: _parse_vec_csv(vr["vector_csv"]) for vr in vec_rows}
            anchor_vecs = [parsed_vecs[t] for t in expanded_terms if t in parsed_vecs and parsed_vecs[t]]
            if anchor_vecs:
                dim = len(anchor_vecs[0])
                q_vec = [sum(v[d] for v in anchor_vecs if len(v) == dim) / len(anchor_vecs) for d in range(dim)]
                sims = []
                for c_name, c_vec in parsed_vecs.items():
                    if c_name not in expanded_terms and c_vec:
                        s = _cosine(q_vec, c_vec)
                        if s >= 0.35:
                            sims.append((c_name, s))
                sims.sort(key=lambda x: x[1], reverse=True)
                vec_neighbors = sims[:4]
                for v_concept, v_sim in vec_neighbors[:2]:
                    for r in c.execute(
                        "SELECT id, source_type, text_content, timestamp FROM memories "
                        "WHERE text_content LIKE ? ESCAPE '\\' ORDER BY timestamp DESC LIMIT 5",
                        (f"%{_escape_like(v_concept)}%",)
                    ).fetchall():
                        add_score(dict(r), 2.8 * v_sim)

        # Temporal window filter ("yesterday", "today", "நேற்று", "இன்று")
        tw = _resolve_temporal_window(q_lower)
        matched_episodes = {}
        if tw:
            s_ms, e_ms, tw_label = tw
            for r in c.execute(
                "SELECT id, source_type, text_content, timestamp FROM memories "
                "WHERE timestamp BETWEEN ? AND ? ORDER BY timestamp DESC LIMIT 20",
                (s_ms, e_ms)
            ).fetchall():
                add_score(dict(r), 4.5)
            for er in c.execute(
                "SELECT * FROM episodes WHERE end_millis >= ? AND start_millis <= ? ORDER BY end_millis DESC LIMIT 4",
                (s_ms, e_ms)
            ).fetchall():
                matched_episodes[er["episode_id"]] = dict(er)
        else:
            tw_label = None

        for tok in expanded_terms[:6]:
            st = _escape_like(tok)
            for er in c.execute(
                "SELECT * FROM episodes WHERE key_concepts LIKE ? ESCAPE '\\' OR narrative_summary LIKE ? ESCAPE '\\' "
                "ORDER BY end_millis DESC LIMIT 3",
                (f"%{st}%", f"%{st}%")
            ).fetchall():
                matched_episodes[er["episode_id"]] = dict(er)

        matched_nodes_map = {}
        for tok in expanded_terms:
            safe_tok = _escape_like(tok)
            for nr in c.execute(
                "SELECT id, label, entity_type, mention_count FROM graph_nodes "
                "WHERE LOWER(label) LIKE ? ESCAPE '\\' ORDER BY mention_count DESC LIMIT 5",
                (f"%{safe_tok}%",)
            ).fetchall():
                d = dict(nr)
                if d["id"] not in matched_nodes_map:
                    matched_nodes_map[d["id"]] = d
        matched_nodes = sorted(matched_nodes_map.values(), key=lambda x: x["mention_count"], reverse=True)

        # Prefer fuzzy-resolved or vocab-matched concept when looking up STDP causal transitions
        prioritized_anchors = [corrected_map.get(t, t) for t in meaningful] + [t for t in expanded_terms if t not in meaningful]
        anchor = None
        causal_next = []
        for cand in prioritized_anchors:
            rows_tr = c.execute(
                "SELECT to_concept, transition_count, weight FROM learned_transitions "
                "WHERE from_concept = ? ORDER BY weight DESC, transition_count DESC LIMIT 3",
                (cand,)
            ).fetchall()
            if rows_tr:
                anchor = cand
                causal_next = [dict(tr) for tr in rows_tr]
                break

    ranked = sorted(scored_memories.values(), key=lambda x: (x["score"], x["row"]["timestamp"]), reverse=True)[:5]

    if not ranked and not matched_nodes and not causal_next and not matched_episodes and not vec_neighbors:
        return {
            "query": query,
            "learned": False,
            "memories_total": total_memories,
            "response": (
                f"⚠️ [No Learned Match • Checked {total_memories} memories / {total_vocab} tokens]\n"
                f"\"{query}\" தொடர்பான எந்தத் தரவும் உங்கள் நினைவுகளில் இல்லை."
            )
        }

    lines = [f"🧠 [CognitiveEngine v4 (Vectors+Episodes+E2EE) • {total_memories} memories • {total_vocab} tokens]"]
    if tw_label:
        lines.append(f"Temporal Window: {tw_label}")
    if fuzzy_matches:
        lines.append(f"Fuzzy / Stem Match: {', '.join(fuzzy_matches)}")
    if causal_next:
        chain_txt = " → ".join(f"{n['to_concept']} (×{n['transition_count']})" for n in causal_next)
        lines.append(f"STDP Causal Next: {anchor} → {chain_txt}")
    if vec_neighbors:
        lines.append("Dense Vector Neighbors: " + ", ".join(f"{c_n} ({v_s:.2f})" for c_n, v_s in vec_neighbors[:3]))
    if matched_nodes:
        entities_str = ", ".join(f"{n['label']} ({n['entity_type']} ×{n['mention_count']})" for n in matched_nodes[:4])
        lines.append(f"Graph Entities: {entities_str}")
    if matched_episodes:
        eps = sorted(matched_episodes.values(), key=lambda e: e["end_millis"], reverse=True)[:2]
        for ep in eps:
            lines.append(f"Episode: {ep['narrative_summary']}")

    ev_lines = []
    for item in ranked:
        r = item["row"]
        ts_str = _fmt_ts(r["timestamp"], "%d %b %I:%M %p")
        content_txt = (r["text_content"] or "").strip() or "Metadata record"
        ev_line = f"• [{r['source_type']} • {ts_str}] {content_txt}"
        lines.append(ev_line)
        ev_lines.append(ev_line)

    slm_summary = _try_local_slm_summary(query, ev_lines)
    if slm_summary:
        lines.insert(1, f"Local SLM Summary: {slm_summary}")

    return {
        "query": query,
        "learned": True,
        "memories_total": total_memories,
        "anchor_concept": anchor or (prioritized_anchors[0] if prioritized_anchors else None),
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
