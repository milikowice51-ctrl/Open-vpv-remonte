"""Android Remote VPN v0.1: TLS pinning, single-use test connection.

No third-party Python dependencies. This component contains no screen control.
"""
from __future__ import annotations
import hashlib
import json
import re
import socket
import ssl
from dataclasses import dataclass
from typing import Any

PORT = 48376
MAX_LINE = 4096


@dataclass
class ProbeResult:
    device: str
    android: str
    address: str
    latency_ms: float
    version: str
    control_enabled: bool
    raw: dict[str, Any]


def clean_hex(text: str, length: int) -> str:
    value = re.sub(r"[:\s-]", "", text).upper()
    if not re.fullmatch(rf"[A-F0-9]{{{length}}}", value):
        raise ValueError(f"Oczekiwano {length} znaków szesnastkowych")
    return value


def read_line(sock: ssl.SSLSocket) -> str:
    data = bytearray()
    while len(data) <= MAX_LINE:
        b = sock.recv(1)
        if not b:
            raise ConnectionError("Połączenie zamknięte")
        if b == b"\n":
            return data.decode("utf-8").rstrip("\r")
        data.extend(b)
    raise ValueError("Zbyt długa odpowiedź z serwera")


def send_line(sock: ssl.SSLSocket, text: str) -> None:
    if "\n" in text or "\r" in text:
        raise ValueError("Niedozwolony znak w żądaniu")
    sock.sendall((text + "\n").encode("ascii"))


def probe(host: str, port: int, key: str, fingerprint: str, timeout: float = 7) -> ProbeResult:
    """Only connect after the owner shares the TLS certificate fingerprint and secret.

    TLS certificate is self-signed. Validation is performed via exact SHA-256 pin.
    No commands or credentials are sent before the fingerprint is verified.
    """
    key = clean_hex(key, 32)
    fingerprint = clean_hex(fingerprint, 64)
    if not host.strip() or not (1 <= port <= 65535):
        raise ValueError("Niepoprawny adres IP lub port")
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE  # exact certificate pin checked *before* auth
    with socket.create_connection((host.strip(), port), timeout=timeout) as raw:
        with ctx.wrap_socket(raw, server_hostname=None) as sock:
            sock.settimeout(timeout)
            cert = sock.getpeercert(binary_form=True)
            if not cert:
                raise ssl.SSLError("Serwer nie przedstawił certyfikatu")
            received = hashlib.sha256(cert).hexdigest().upper()
            if received != fingerprint:
                raise ssl.SSLError("BŁĄD: odcisk TLS nie zgadza się z telefonem — przerwano")
            if read_line(sock) != "HELLO 1":
                raise ConnectionError("Niezgodny protokół serwera")
            send_line(sock, "AUTH " + key)
            response = read_line(sock)
            if response != "OK 1":
                raise PermissionError("Odmowa autoryzacji: " + response)
            from time import monotonic
            started = monotonic()
            send_line(sock, "PING")
            if not read_line(sock).startswith("PONG "):
                raise ConnectionError("Brak odpowiedzi na PING")
            elapsed = (monotonic() - started) * 1000
            send_line(sock, "INFO")
            info_reply = read_line(sock)
            if not info_reply.startswith("INFO "):
                raise ConnectionError("Brak informacji o urządzeniu")
            data = json.loads(info_reply[5:])
            send_line(sock, "QUIT")
            if read_line(sock) != "BYE":
                raise ConnectionError("Błędne zamknięcie sesji")
            return ProbeResult(
                device=str(data.get("device", "?")),
                android=str(data.get("android", "?")),
                address=str(data.get("vpnAddress", "?")),
                latency_ms=round(elapsed, 1),
                version=str(data.get("version", "?")),
                control_enabled=bool(data.get("remoteControl", False)),
                raw=data,
            )

TV_KEYS = ("UP", "DOWN", "LEFT", "RIGHT", "OK", "BACK", "HOME")


def send_tv_key(host: str, port: int, key: str, fingerprint: str,
                tv_key: str, timeout: float = 7) -> str:
    """Send *one* authorized TV navigation command via TLS certificate pinning.

    A fresh connection is used for each command in this prototype; this is
    intentional for simplicity, but unsuitable for fast real-time input.
    """
    key = clean_hex(key, 32)
    fingerprint = clean_hex(fingerprint, 64)
    tv_key = tv_key.upper()
    if tv_key not in TV_KEYS:
        raise ValueError("Niedozwolona komenda TV")
    if not host.strip() or not (1 <= port <= 65535):
        raise ValueError("Niepoprawny adres IP lub port")

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE  # SHA-256 pin is checked before AUTH
    with socket.create_connection((host.strip(), port), timeout=timeout) as raw:
        with ctx.wrap_socket(raw, server_hostname=None) as sock:
            sock.settimeout(timeout)
            cert = sock.getpeercert(binary_form=True)
            if not cert:
                raise ssl.SSLError("Brak certyfikatu hosta")
            if hashlib.sha256(cert).hexdigest().upper() != fingerprint:
                raise ssl.SSLError("Certyfikat TV jest inny niż zapamiętany — komenda odrzucona")
            if read_line(sock) != "HELLO 1":
                raise ConnectionError("Nieobsługiwany protokół hosta")
            send_line(sock, "AUTH " + key)
            if read_line(sock) != "OK 1":
                raise PermissionError("Błąd kodu sesji lub odmowa autoryzacji")
            send_line(sock, "KEY " + tv_key)
            result = read_line(sock)
            if result != "OK KEY " + tv_key:
                if not result.startswith("ERR "):
                    raise ConnectionError("Nieznana odpowiedź hosta")
                raise PermissionError("TV odrzucił polecenie: " + result[4:])
            send_line(sock, "QUIT")
            if read_line(sock) != "BYE":
                raise ConnectionError("Niepoprawne zamknięcie połączenia")
            return result
