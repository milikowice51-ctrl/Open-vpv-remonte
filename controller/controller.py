"""Remote VPN desktop controller: diagnostics + authorized Android TV D-pad."""
import argparse
import sys
import threading
import tkinter as tk
from tkinter import ttk
from tkinter.scrolledtext import ScrolledText
from protocol import PORT, probe, send_tv_key, TV_KEYS
from trusted import (DEFAULT_IDENTITY, pair_controller, trusted_probe, trusted_key)
from pathlib import Path


class Controller(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Android Remote VPN — kontroler TV v0.4")
        self.geometry("735x850")
        self.minsize(610, 710)
        self.configure(bg="#eef2f7")
        self.command_lock = threading.Lock()
        frame = ttk.Frame(self, padding=18)
        frame.pack(fill="both", expand=True)
        ttk.Label(frame, text="ANDROID REMOTE VPN — PILOT TV",
                  font=("Segoe UI", 18, "bold")).pack(anchor="w")
        ttk.Label(frame, text="Sterowanie Android TV przez prywatną sieć OpenVPN; bez podglądu obrazu w v0.3").pack(anchor="w", pady=(4, 10))
        self.entries = {}
        for label, name, default, secret in [
            ("Adres VPN telewizora", "host", "10.8.0.6", False),
            ("Port", "port", str(PORT), False),
            ("Kod sesji starszego trybu (opcjonalny)", "key", "", True),
            ("Kod pierwszego parowania z TV (12 znaków)", "pair_code", "", True),
            ("Nazwa kontrolera (na TV)", "name", "Mój komputer", False),
            ("Plik klucza kontrolera (tylko lokalnie)", "identity", str(DEFAULT_IDENTITY), False),
            ("Odcisk certyfikatu SHA-256 (64 znaków HEX)", "fp", "", False),
        ]:
            ttk.Label(frame, text=label).pack(anchor="w", pady=(5, 1))
            entry = ttk.Entry(frame, show="•" if secret else "")
            entry.insert(0, default)
            entry.pack(fill="x", ipady=3)
            self.entries[name] = entry

        self.pairbtn = ttk.Button(frame, text="PARUJ Z TV (zatwierdź na telewizorze)", command=self.pair)
        self.pairbtn.pack(anchor="w", pady=(8, 4))
        self.btn = ttk.Button(frame, text="Sprawdź zaufane połączenie z TV", command=self.connect)
        self.btn.pack(anchor="w", pady=(12, 6))

        pad = ttk.LabelFrame(frame, text="Zdalny pilot (wymagana zgoda na TV)", padding=12)
        pad.pack(fill="x", pady=(8, 12))
        commands = [
            ("⌂ HOME", "HOME", 0, 0),
            ("↑", "UP", 0, 1),
            ("↩ BACK", "BACK", 0, 2),
            ("←", "LEFT", 1, 0),
            ("OK", "OK", 1, 1),
            ("→", "RIGHT", 1, 2),
            ("↓", "DOWN", 2, 1),
        ]
        for label, code, r, c in commands:
            btn = ttk.Button(pad, text=label, command=lambda code=code: self.send_key(code))
            btn.grid(row=r, column=c, sticky="nsew", padx=5, pady=5, ipady=7)
        for c in range(3):
            pad.grid_columnconfigure(c, weight=1)

        ttk.Label(frame, text="Zdarzenia i diagnostyka", font=("Segoe UI", 11, "bold")).pack(anchor="w")
        self.output = ScrolledText(frame, height=8, wrap="word", state="disabled")
        self.output.pack(fill="both", expand=True, pady=(5, 8))
        ttk.Label(frame,
                  text="Host musi mieć aktywną usługę dostępności, włączone sterowanie i aktywny VPN."
                  ).pack(anchor="w")

    def log(self, value):
        self.output.configure(state="normal")
        self.output.insert("end", value + "\n")
        self.output.see("end")
        self.output.configure(state="disabled")

    def params(self):
        return {name: field.get() for name, field in self.entries.items()}

    def connect(self):
        params = self.params()
        self.btn.configure(state="disabled")
        self.log("Weryfikuję certyfikat TLS i podpis klucza kontrolera...")

        def work():
            try:
                if Path(params["identity"]).expanduser().exists():
                    result = trusted_probe(params["host"], int(params["port"]), params["fp"],
                                           Path(params["identity"]).expanduser())
                else:
                    result = probe(params["host"], int(params["port"]), params["key"], params["fp"])
                output = (f"POŁĄCZONO ✓ | {result.device} | Android {result.android}"
                          f"\nIP VPN: {result.address} | RTT: {result.latency_ms} ms"
                          f"\nSterowanie aktywne: {'TAK' if result.control_enabled else 'NIE'}"
                          f" | Obsługa D-pad Android 13+: {'TAK' if result.raw.get('tvDpadSupported') else 'NIE / brak danych'}"
                          "\nPodgląd obrazu: jeszcze niedostępny")
            except Exception as error:
                output = "BŁĄD: " + str(error)
            self.after(0, lambda: (self.log(output), self.btn.configure(state="normal")))
        threading.Thread(target=work, daemon=True).start()

    def pair(self):
        params = self.params()
        self.pairbtn.configure(state="disabled")
        self.log("Oczekuję na parowanie. Potwierdź nazwę i odcisk klucza na TV.")

        def work():
            try:
                device_id = pair_controller(params["host"], int(params["port"]), params["fp"],
                                            params["pair_code"], params["name"],
                                            Path(params["identity"]).expanduser())
                result = "SPAROWANO: " + device_id + "\nKod sesji nie będzie już potrzebny."
            except Exception as error:
                result = "BŁĄD PAROWANIA: " + str(error)
            self.after(0, lambda: (self.log(result), self.pairbtn.configure(state="normal")))

        threading.Thread(target=work, daemon=True).start()

    def send_key(self, tv_key):
        params = self.params()
        if not self.command_lock.acquire(blocking=False):
            self.log("Poprzednia komenda jeszcze trwa")
            return
        def work():
            try:
                if Path(params["identity"]).expanduser().exists():
                    trusted_key(params["host"], int(params["port"]), params["fp"], tv_key,
                                Path(params["identity"]).expanduser())
                else:
                    send_tv_key(params["host"], int(params["port"]), params["key"], params["fp"], tv_key)
                output = "TV ← " + tv_key + " ✓"
            except Exception as error:
                output = tv_key + " — " + str(error)
            finally:
                self.command_lock.release()
            self.after(0, lambda: self.log(output))
        threading.Thread(target=work, daemon=True).start()


def main():
    parser = argparse.ArgumentParser(description="Android Remote VPN — pilot TV i diagnostyka")
    parser.add_argument("--host", help="Adres IP VPN urządzenia Android TV")
    parser.add_argument("--port", type=int, default=PORT)
    parser.add_argument("--key", help="Kod sesji HEX")
    parser.add_argument("--fingerprint", help="SHA-256 certyfikatu TLS")
    parser.add_argument("--send-key", choices=TV_KEYS, help="Komenda pilota (np. HOME, OK, RIGHT)")
    parser.add_argument("--trusted", action="store_true", help="Podpis kluczem kontrolera zamiast kodu sesji")
    parser.add_argument("--identity", default=str(DEFAULT_IDENTITY), help="Ścieżka do klucza ECDSA kontrolera")
    parser.add_argument("--pair-code", help="12-znakowy kod parowania wyświetlany na TV")
    parser.add_argument("--name", default="Mój komputer", help="Nazwa nowego kontrolera")
    args = parser.parse_args()
    if args.host:
        if not args.fingerprint:
            parser.error("CLI wymaga --fingerprint")
        try:
            if args.pair_code:
                print("SPAROWANO:", pair_controller(args.host, args.port, args.fingerprint,
                      args.pair_code, args.name, Path(args.identity)))
            elif args.trusted:
                if args.send_key:
                    print(trusted_key(args.host, args.port, args.fingerprint, args.send_key, Path(args.identity)))
                else:
                    print("OK:", trusted_probe(args.host, args.port, args.fingerprint, Path(args.identity)))
            elif args.key:
                if args.send_key:
                    print(send_tv_key(args.host, args.port, args.key, args.fingerprint, args.send_key))
                else:
                    print("OK:", probe(args.host, args.port, args.key, args.fingerprint))
            else:
                parser.error("Wybierz --trusted, --pair-code albo --key")
        except Exception as error:
            print("ERROR:", error, file=sys.stderr)
            return 1
        return 0
    if args.send_key or args.trusted or args.pair_code:
        parser.error("Opcje CLI wymagają również --host")
    Controller().mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
