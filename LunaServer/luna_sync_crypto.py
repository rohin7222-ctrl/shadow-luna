"""
Pure Python stdlib End-to-End Encrypted LAN Sync Crypto Engine (NIST P-256 ECDH + AES-256-CTR + HMAC-SHA256).
Zero third-party packages required; binary-compatible with Android SyncCryptoUtil.kt (API 26+).
"""
import base64
import hashlib
import hmac
import secrets

# NIST P-256 (secp256r1) domain parameters
_P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
_A = _P - 3
_B = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B
_N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
_GX = 0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296
_GY = 0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5

# FIPS-197 AES S-Box & Rcon
_SBOX = (
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
)
_RCON = (0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36)


def _point_add(p1, p2):
    if p1 is None:
        return p2
    if p2 is None:
        return p1
    x1, y1 = p1
    x2, y2 = p2
    if x1 == x2:
        if (y1 + y2) % _P == 0:
            return None
        lam = ((3 * x1 * x1 + _A) * pow((2 * y1) % _P, -1, _P)) % _P
    else:
        lam = ((y2 - y1) * pow((x2 - x1) % _P, -1, _P)) % _P
    x3 = (lam * lam - x1 - x2) % _P
    y3 = (lam * (x1 - x3) - y1) % _P
    return (x3, y3)


def _scalar_mult(k: int, point):
    result = None
    addend = point
    while k:
        if k & 1:
            result = _point_add(result, addend)
        addend = _point_add(addend, addend)
        k >>= 1
    return result


def generate_ecdh_keypair():
    priv = 1 + secrets.randbelow(_N - 1)
    pub = _scalar_mult(priv, (_GX, _GY))
    return priv, encode_pubkey_b64(pub)


def encode_pubkey_b64(pub: tuple[int, int]) -> str:
    raw = b"\x04" + pub[0].to_bytes(32, "big") + pub[1].to_bytes(32, "big")
    return base64.b64encode(raw).decode("ascii")


def decode_pubkey_b64(b64_str: str) -> tuple[int, int]:
    raw = base64.b64decode(b64_str.strip())
    if len(raw) != 65 or raw[0] != 0x04:
        raise ValueError("Invalid uncompressed P-256 public key")
    x = int.from_bytes(raw[1:33], "big")
    y = int.from_bytes(raw[33:65], "big")
    if (y * y - (x * x * x + _A * x + _B)) % _P != 0:
        raise ValueError("Public key point not on NIST P-256 curve")
    return (x, y)


def compute_shared_secret(priv: int, peer_pub_b64: str) -> bytes:
    peer = decode_pubkey_b64(peer_pub_b64)
    pt = _scalar_mult(priv, peer)
    if pt is None:
        raise ValueError("ECDH produced point at infinity")
    return pt[0].to_bytes(32, "big")


def derive_session_keys(shared_secret: bytes, session_salt: str) -> tuple[bytes, bytes]:
    salt_b = session_salt.encode("utf-8")
    enc_key = hashlib.sha256(b"SHADOW-ENC-V1|" + shared_secret + salt_b).digest()
    mac_key = hashlib.sha256(b"SHADOW-MAC-V1|" + shared_secret + salt_b).digest()
    return enc_key, mac_key


def _xtime(a: int) -> int:
    return ((a << 1) ^ 0x1B) & 0xFF if (a & 0x80) else (a << 1)


def _aes256_expand_key(key: bytes) -> list[list[int]]:
    nk = 8
    w = [[key[4 * i], key[4 * i + 1], key[4 * i + 2], key[4 * i + 3]] for i in range(nk)]
    for i in range(nk, 60):
        temp = list(w[i - 1])
        if i % nk == 0:
            temp = [_SBOX[temp[1]] ^ _RCON[i // nk], _SBOX[temp[2]], _SBOX[temp[3]], _SBOX[temp[0]]]
        elif i % nk == 4:
            temp = [_SBOX[b] for b in temp]
        prev = w[i - nk]
        w.append([prev[j] ^ temp[j] for j in range(4)])
    return w


def _aes256_encrypt_block(block: bytes, w: list[list[int]]) -> bytes:
    # State stored column-major: s[row + 4*col] -> s[4*col + row] == block[4*col + row]
    s = list(block)

    def add_rk(r: int):
        base = r * 4
        for c in range(4):
            wk = w[base + c]
            s[4 * c] ^= wk[0]
            s[4 * c + 1] ^= wk[1]
            s[4 * c + 2] ^= wk[2]
            s[4 * c + 3] ^= wk[3]

    add_rk(0)
    for r in range(1, 15):
        for i in range(16):
            s[i] = _SBOX[s[i]]
        # ShiftRows on column-major 4x4 state
        s[1], s[5], s[9], s[13] = s[5], s[9], s[13], s[1]
        s[2], s[6], s[10], s[14] = s[10], s[14], s[2], s[6]
        s[3], s[7], s[11], s[15] = s[15], s[3], s[7], s[11]
        if r < 14:
            for c in range(4):
                i = 4 * c
                a0, a1, a2, a3 = s[i], s[i + 1], s[i + 2], s[i + 3]
                t = a0 ^ a1 ^ a2 ^ a3
                s[i] ^= t ^ _xtime(a0 ^ a1)
                s[i + 1] ^= t ^ _xtime(a1 ^ a2)
                s[i + 2] ^= t ^ _xtime(a2 ^ a3)
                s[i + 3] ^= t ^ _xtime(a3 ^ a0)
        add_rk(r)
    return bytes(s)


def _aes256_ctr_xor(data: bytes, enc_key: bytes, iv16: bytes) -> bytes:
    w = _aes256_expand_key(enc_key)
    counter = int.from_bytes(iv16, "big")
    out = bytearray(len(data))
    for off in range(0, len(data), 16):
        ks = _aes256_encrypt_block((counter & ((1 << 128) - 1)).to_bytes(16, "big"), w)
        counter += 1
        chunk = data[off:off + 16]
        for j, b in enumerate(chunk):
            out[off + j] = b ^ ks[j]
    return bytes(out)


def decrypt_envelope(cipher_b64: str, iv_b64: str, hmac_b64: str, enc_key: bytes, mac_key: bytes) -> bytes:
    iv = base64.b64decode(iv_b64)
    ct = base64.b64decode(cipher_b64)
    tag = base64.b64decode(hmac_b64)
    expected = hmac.new(mac_key, iv + ct, hashlib.sha256).digest()
    if not hmac.compare_digest(tag, expected):
        raise ValueError("E2EE HMAC verification failed")
    return _aes256_ctr_xor(ct, enc_key, iv)


def encrypt_envelope(plaintext: bytes, enc_key: bytes, mac_key: bytes) -> tuple[str, str, str]:
    iv = secrets.token_bytes(16)
    ct = _aes256_ctr_xor(plaintext, enc_key, iv)
    tag = hmac.new(mac_key, iv + ct, hashlib.sha256).digest()
    return (
        base64.b64encode(ct).decode("ascii"),
        base64.b64encode(iv).decode("ascii"),
        base64.b64encode(tag).decode("ascii"),
    )
