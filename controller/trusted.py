"""v0.4 trusted device pairing (P-256 ECDSA challenge/response over pinned TLS).

Requires `pip install cryptography`. The desktop private key is a local account
secret: do not share the PEM with anyone. Host always verifies TLS certificate
fingerprint before authentication or sending a pairing code.
"""
from __future__ import annotations
import base64
from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import socket
import ssl
import time
from typing import Generator
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.asymmetric import ec
from protocol import PORT, TV_KEYS, ProbeResult, read_line, send_line, clean_hex

DEFAULT_IDENTITY = Path.home() / ".android_remote_vpn" / "controller-identity.pem"


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def _pub_der(private) -> bytes:
    return private.public_key().public_bytes(serialization.Encoding.DER,
                                             serialization.PublicFormat.SubjectPublicKeyInfo)


def identity_id(private) -> str:
    return hashlib.sha256(_pub_der(private)).hexdigest().upper()


def load_or_create_identity(path: Path = DEFAULT_IDENTITY):
    path = Path(path).expanduser()
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        private = ec.generate_private_key(ec.SECP256R1())
        content = private.private_bytes(serialization.Encoding.PEM,
                                        serialization.PrivateFormat.PKCS8,
                                        serialization.NoEncryption())
        # Exclusive creation. Never overwrite an existing key or silently re-pair.
        try:
            fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            pass
        else:
            with os.fdopen(fd, "wb") as output:
                output.write(content)
                output.flush()
                os.fsync(output.fileno())
    if os.name != "nt":
        if path.stat().st_mode & 0o077:
            raise PermissionError(f"Klucz ma zbyt szerokie prawa. Ustaw chmod 600 '{path}'")
    key = serialization.load_pem_private_key(path.read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("Wymagany klucz ECDSA P-256")
    return key


@contextmanager
def verified_tls(host: str, port: int, fingerprint: str, timeout: float = 8):
    pin = clean_hex(fingerprint, 64)
    if not host.strip() or not 0 < port < 65536:
        raise ValueError("Niepoprawny adres hosta/port")
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE  # exact pin verified before sending any data
    with socket.create_connection((host.strip(), port), timeout=timeout) as raw:
        with ctx.wrap_socket(raw, server_hostname=None) as sock:
            sock.settimeout(timeout)
            cert = sock.getpeercert(binary_form=True)
            if not cert or hashlib.sha256(cert).hexdigest().upper() != pin:
                raise ssl.SSLError("Odcisk TLS hosta niezgodny — brak uwierzytelnienia")
            if read_line(sock) != "HELLO 1":
                raise ConnectionError("Niezgodna wersja protokołu")
            yield sock, pin


def pair_controller(host: str, port: int, fingerprint: str, code: str,
                    controller_name: str, key_path: Path = DEFAULT_IDENTITY,
                    wait_seconds: int = 120) -> str:
    code = code.strip().upper()
    if len(code) != 12 or not code.isalnum():
        raise ValueError("Kod parowania musi mieć 12 znaków z telewizora")
    name = controller_name.strip()
    if not name or len(name) > 40:
        raise ValueError("Podaj nazwę urządzenia (1–40 znaków)")
    private = load_or_create_identity(key_path)
    device_id = identity_id(private)
    with verified_tls(host, port, fingerprint) as (sock, _):
        send_line(sock, "PAIR " + code + " " + b64url(name.encode("utf8")) +
                  " " + b64url(_pub_der(private)))
        reply = read_line(sock)
    if not reply.startswith("PENDING "):
        raise PermissionError("TV odrzucił żądanie parowania: " + reply)
    token = clean_hex(reply[8:], 32)
    deadline = time.monotonic() + min(wait_seconds, 120)
    while time.monotonic() < deadline:
        time.sleep(2)
        with verified_tls(host, port, fingerprint) as (sock, _):
            send_line(sock, "PAIR_STATUS " + token)
            status = read_line(sock)
        if status == "PENDING":
            continue
        if status == "PAIRED " + device_id:
            return device_id
        raise PermissionError("Parowanie odrzucone lub wygasło: " + status)
    raise TimeoutError("Nie zatwierdzono parowania na telewizorze")


@contextmanager
def trusted_session(host: str, port: int, fingerprint: str,
                    key_path: Path = DEFAULT_IDENTITY):
    private = load_or_create_identity(key_path)
    device_id = identity_id(private)
    with verified_tls(host, port, fingerprint) as (sock, pin):
        send_line(sock, "LOGIN " + device_id)
        response = read_line(sock)
        if not response.startswith("CHALLENGE "):
            raise PermissionError("Kontroler nie jest zaufany lub dostęp wstrzymany: " + response)
        nonce = clean_hex(response[10:], 64)
        signed = ("ARDVPN-LOGIN-V1|" + pin + "|" + device_id + "|" + nonce).encode("ascii")
        signature = private.sign(signed, ec.ECDSA(hashes.SHA256()))
        send_line(sock, "SIGN " + b64url(signature))
        if (status := read_line(sock)) != "OK 1":
            raise PermissionError("Nieprawidłowy podpis klucza kontrolera: " + status)
        yield sock
        try:
            send_line(sock, "QUIT")
            read_line(sock)
        except (ConnectionError, OSError, ssl.SSLError):
            pass


def trusted_key(host: str, port: int, fingerprint: str, tv_key: str,
                key_path: Path = DEFAULT_IDENTITY) -> str:
    tv_key = tv_key.upper()
    if tv_key not in TV_KEYS:
        raise ValueError("Niedozwolona komenda pilota")
    with trusted_session(host, port, fingerprint, key_path) as sock:
        send_line(sock, "KEY " + tv_key)
        result = read_line(sock)
        if result != "OK KEY " + tv_key:
            raise PermissionError("TV odrzucił sterowanie: " + result)
        return result


def trusted_probe(host: str, port: int, fingerprint: str,
                  key_path: Path = DEFAULT_IDENTITY) -> ProbeResult:
    with trusted_session(host, port, fingerprint, key_path) as sock:
        start = time.monotonic()
        send_line(sock, "PING")
        if not read_line(sock).startswith("PONG "):
            raise ConnectionError("Niepoprawne PONG")
        latency = round((time.monotonic() - start) * 1000, 1)
        send_line(sock, "INFO")
        response = read_line(sock)
        if not response.startswith("INFO "):
            raise ConnectionError("Niepoprawne INFO: " + response)
        data = json.loads(response[5:])
        return ProbeResult(device=str(data.get("device", "?")),
                           android=str(data.get("android", "?")),
                           address=str(data.get("vpnAddress", "?")),
                           latency_ms=latency,
                           version=str(data.get("version", "?")),
                           control_enabled=bool(data.get("remoteControl", False)), raw=data)
