## Rozszerzenie v0.4: trwałe parowanie

Host: `TrustedControllers.java` (lista kluczy + preferencje + cofanie dostępu),
`PairingGate.java` (jednorazowy kod z zatwierdzeniem właściciela),
`RemoteHostService.java` (`PAIR`, `PAIR_STATUS`, `LOGIN`, `SIGN`; ponowne sprawdzenie uprawnień
przed każdą komendą), `MainActivity.java` (lokalne zgody i lista zaufanych).
Kontrolery: `controller/trusted.py` (ECDSA P-256, klucz PEM użytkownika),
`tv-controller/.../TrustedTvClient.java` (klucz w Android Keystore).

Zaprojektowany przepływ: TV ↔ własny OpenVPN ↔ kontroler, TLS 1.2+ z pinningiem
SHA-256, podpis klucza kontrolera i weryfikacja ACL na każdej komendzie.

W v0.4 `extra_banner=false` oznacza brak **dodatkowego banera w aktywności hosta**;
nie wyłącza powiadomienia usługi, systemowych wskaźników ani zgód Androida.
Zob. [`PAROWANIE.md`](PAROWANIE.md).

---

# Docelowa architektura: Android Remote VPN

## Zasada produktu

Każdy użytkownik sam wybiera serwer OpenVPN i dostarcza poprawny profil `.ovpn` oraz własne dane uwierzytelniające. Dostawca aplikacji nie otrzymuje dostępu do VPN, ruchu ani ekranu. Kontroler ma routowalny dostęp do adresu VPN telefonu. Nie ma automatycznego przekierowania portu z Internetu.

## Moduły

```text
Android Remote Host (Android APK)
  |-- Profile Importer (SAF, .ovpn)
  |-- Credential Vault (Android Keystore, AES-GCM)
  |-- OpenVPN Engine Adapter (v0.1: ICS OpenVPN AIDL; później embed)
  |-- VPN Address Monitor (ConnectivityManager / VPN transport)
  |-- Session Manager (włączenie ręczne, jawne potwierdzenie i odwołanie)
  |-- Authenticated TLS Server (cert Android Keystore, fingerprint pinning)
  |-- Protocol Handler v1 (HELLO/AUTH/PING/INFO/QUIT)
  |-- Video Capture [PLAN] (MediaProjection + wideo H.264)
  |-- Remote Input [PLAN] (AccessibilityService; świadome włączenie)
  |-- Notifications and Audit [PLAN] (trwałe powiadomienie, dziennik sesji)

Windows Remote Controller
  |-- VPN tunnel (na PC: istniejący klient VPN użytkownika)
  |-- Certificate Verifier (pin SHA-256)
  |-- Session Authentication (losowy kod z telefonu)
  |-- Transport (TCP over TLS)
  |-- Device Diagnostics (v0.1)
  |-- H.264 Renderer [PLAN]
  |-- Mouse/Touch Mapping [PLAN]
  |-- Clipboard/Text Input [PLAN, po osobnej zgodzie]
```

## Etap 1 / protokół `1` (istniejący w źródłach)

1. Telefon posiada aktywny VPN i wyświetla przydzielony adres.
2. Właściciel uruchamia `RemoteHostService`. Serwer *bind* do adresu IPv4 interfejsu VPN, port `48376`.
3. Serwer generuje losowy 128-bitowy kod sesji i pokazuje lokalny odcisk SHA-256 certyfikatu TLS.
4. Kontroler zestawia TLS 1.2+ i porównuje certyfikat z odciskiem otrzymanym od właściciela. Jeśli odcisk nie pasuje, **nie wysyła klucza sesji**.
5. Serwer wysyła `HELLO 1`; klient wysyła `AUTH <32_HEX>`.
6. Serwer porównuje sekrety w stałym czasie i odpowiada `OK 1` albo `ERR BAD_KEY`.
7. Autoryzowany klient wysyła `PING`, `INFO`, `QUIT`; uzyskuje `PONG <ms>`, `INFO <json>`, `BYE`.
8. Po wyłączeniu aplikacji-host lub utracie VPN sesja wygasa; serwer nie ma komendy startu przechwytywania ekranu.

**Ograniczenia transportu v0.1:** IPv4 tylko, jeden klient na raz, protokół liniowy do diagnostyki. Dla strumieniowania wideo przewidziany osobny kanał binarny z framingiem i limitami. Autoryzacja będzie docelowo oparta na sparowanych kluczach klienta, nie na przepisywaniu kodu przy każdym połączeniu.

## Docelowy protokół v2 (plan, niezaimplementowany)

- TLS 1.3 preferowane, wzajemne uwierzytelnianie urządzeń i przypięte klucze; wstępne parowanie QR zawierające fingerprint i jednorazowy token.
- Kontrola uprawnień per sesja: `VIEW_ONLY`, `CONTROL`, `CLIPBOARD`, `FILE_TRANSFER` — domyślnie tylko widok i tylko po zgodzie.
- Właściciel zatwierdza żądanie sesji, widzi nazwę kontrolera, może natychmiast odłączyć.
- Kanał sterowania: komendy `REQUEST_SCREEN`, `STOP_SCREEN`, `MOUSE_MOVE`, `TAP`, `SWIPE`, `KEY`, `BACK`, `HOME`, `ROTATION` z monotonicznymi numerami i limitami szybkości.
- Obraz: `MediaProjection` -> `VirtualDisplay` -> `MediaCodec` H.264 -> opakowane ramki o ograniczonym rozmiarze -> kanał wideo TLS -> dekoder sprzętowy na PC.
- Dostępność: wydzielona usługa `AccessibilityService` z `canPerformGestures=true`, bez automatycznego włączania; wyłącznie po autoryzacji i zgodzie na aktywną sesję.
- Rotacja ekranu: aktualizacja `VirtualDisplay` zamiast powtórnego wykorzystania tokenu `MediaProjection`.
- Ochrona materiałów `FLAG_SECURE`: nie omijać blokad aplikacji bankowych ani DRM.
- Utrata VPN lub certyfikatu: natychmiastowe zakończenie wszystkich sesji.

## Etapy rozwoju

| Etap | Kryterium ukończenia |
|---|---|
| 1 (ten projekt) | Android importuje `.ovpn`, uruchamia tunel przez zewnętrzny silnik, serwer TLS odpowiada na PING/INFO, kontroler weryfikuje odcisk |
| 2 | Właściciel może świadomie zaakceptować sesję podglądu; kontroler wyświetla strumień H.264, zatrzymanie zawsze działa |
| 3 | Po włączeniu dostępności i zgody na sterowanie działa TAP/SWIPE/BACK/HOME; możliwość widok-only |
| 4 | Pełna obsługa profili OpenVPN i wbudowany silnik VPN, konfiguracje z hasłami oraz certyfikatami zewnętrznymi |
| 5 | Kontroler Android, podpisywanie APK, CI i testy kompatybilności, dokumentacja licencji, przygotowanie dystrybucji |

## Testy niezbędne przed publikacją

- Różne wersje Androida (8–17), rotacja, wymuszone ubicie procesu, restart, blokada ekranu, odłączanie VPN.
- Przypadki sieciowe: IPv4/IPv6, split tunnel, brak client-to-client, ponowne łączenie, zmiana adresu, utrata Wi-Fi/LTE.
- Test penetracyjny TLS/pinning, replay, błędne certyfikaty, obciążenie wielu klientów, DoS wewnątrz VPN.
- Obciążenie CPU i baterii oraz latency 480p/720p/1080p przy ruchu LTE/Wi-Fi.
- Funkcje ochrony prywatności i przejrzyste zgody Androida; weryfikacja polityk sklepu dla AccessibilityService i VPN.

## Rozszerzenie v0.2: Android TV / Google TV Controller

Projekt Android Studio `android-host/` zawiera teraz **dwa niezależne moduły** APK:

- `:app` — Android Remote Host (telefon / tablet, protokół TLS v1);
- `:tv-controller` — Android Remote TV Controller (Android TV / Google TV).

Wersja TV ma interfejs sterowany pilotem D-pad, launcher Leanback, baner TV,
import profilu `.ovpn` szyfrowanego w Android Keystore, opcjonalne połączenie
przez zewnętrzną aplikację `OpenVPN for Android` (jeśli wspierana na danym TV),
oraz klienta TLS do odczytu `PING`/`INFO`. Klient weryfikuje SHA-256 certyfikatu
zanim wyśle kod sesji. Nie zapisuje kodu ani odcisku do preferencji.

Alternatywa dla klienta VPN na TV: tunel OpenVPN na routerze i routing TV ↔ host,
bez konieczności instalowania VPN na samym TV. Wymagana jest osiągalność IPv4
adresu hosta i zezwolenie na komunikację między klientami po stronie serwera.

**Do zrobienia:** prawdziwy strumień H.264 i renderowanie przez MediaCodec;
sterowanie kursorem pilotem D-pad, urządzenia HID Bluetooth/USB,
jednorazowe parowanie z użyciem QR/krótkiego kodu i prywatnego zaufania do urządzenia.
Dotychczasowy protokół v1 *nie pozwala* pobierać ekranu ani wysyłać gestów.

Wsparcie Android TV/Google TV nie oznacza kompatybilności z Tizen, webOS lub
wszystkimi producentami "Smart TV". Sideloading, selektor dokumentów i
zewnętrzny silnik OpenVPN wymagają weryfikacji na docelowym modelu.


## v0.3 — Android TV jako urządzenie sterowane (priorytet)

Implementacja 0.3 dodaje do modułu `:app` deklaracje `LEANBACK_LAUNCHER`,
`android.software.leanback`, `touchscreen required=false` i ikonę/baner TV;
nie jest to oddzielny host TV — ta sama aplikacja może być uruchamiana na
telefonie lub telewizorze (z zastrzeżeniami obsługi D-pad na starszych wersjach).
Dla drugiego kierunku nadal dostępny jest moduł `:tv-controller`.

`TvRemoteAccessibilityService`: włączana ręcznie przez właściciela, świadomie
nie odczytuje hierarchii okien i nie wykonuje gestów dotykowych. Udostępnia
jedynie `performGlobalAction` dla HOME/BACK i (API 33+) D-pad/OK.
`RemoteHostService` przyjmuje po TLS `KEY UP|DOWN|LEFT|RIGHT|OK|BACK|HOME`,
ale tylko po dwustopniowym warunku: autoryzacja 128-bitowym kodem sesji
oraz lokalne włączenie sterowania na ekranie TV. Odcisk certyfikatu TLS
musi odpowiadać temu, który widzi właściciel TV. Utrata VPN/zatrzymanie hosta
kasuje sesję i uprawnienie `controlArmed`; nie ma komendy API do jego włączenia.
Usługa działa w foreground z widocznym powiadomieniem i przyciskiem STOP.

Kontroler Windows `controller.py` oraz kontroler Android TV `:tv-controller`
wysyłają te same komendy. Sterowanie **nie zawiera na razie obrazu ani dźwięku**.
Obraz będzie kolejną fazą: MediaProjection (zgoda na każdą sesję dla nowszych
Androidów), sprzętowy koder H.264 i dekoder w kontrolerach. Treści DRM i
okna `FLAG_SECURE` mogą być niewidoczne i nie będą obchodzone.

Ograniczenia: (1) nie wszystkie TV zezwalają na włączenie dostępu do usługi
AccessibilityService dla aplikacji spoza sklepu; (2) Android TV starszy niż 13
nie posiada standardowych GLOBAL_ACTION_DPAD_*; (3) host wymaga VPN na TV,
nie wystarcza samo połączenie przez VPN na routerze; (4) nie ma budzenia
wyłączonego telewizora i usługa może być zatrzymywana przez system/OEM.

Następny etap: podgląd ekranu, fizyczna weryfikacja ograniczeń na TV,
lepsze parowanie (QR lub jednorazowy PIN), kontrola sesji i audyt, trwałe
zaufanie do wybranych kontrolerów za potwierdzeniem właściciela.
