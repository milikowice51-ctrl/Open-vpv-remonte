# Parowanie zaufanych kontrolerów — v0.4

## Zakres i bieżący status

Implementacja źródłowa dla Android Host (`:app`), Windows (`controller/`) i Android TV Controller
(`:tv-controller`). Brak kompilacji APK w tym środowisku; testy z użyciem symulatora TLS
potwierdzają protokół desktopowy, ale **nie gwarantują działania na konkretnym TV**.

## Ustawienia właściciela TV

- `auto_accept=true`: klienci już sparowani logują się podpisem klucza, bez ponownego kodu sesji,
  o ile host pracuje, TV ma aktywny VPN i usługę dostępności, a dostęp nie został wstrzymany.
- `pilot=true`: pozwala na komendy `UP`, `DOWN`, `LEFT`, `RIGHT`, `OK`, `BACK`, `HOME`.
  D-pad i OK wymagają Androida 13 (API 33); Home/Back działają także na starszych.
- `extra_banner=false`: brak dodatkowego banera w interfejsie aplikacji po zdalnym poleceniu.
  **Nie usuwa** powiadomienia serwera z akcją ZATRZYMAJ, indykatorów Androida ani zgód systemowych.
- `paused=false`: blokada awaryjna jest domyślnie wyłączona. Przycisk WSTRZYMAJ blokuje wszystkie
  polecenia, również z już otwartych połączeń. Wyłączenie blokady wymaga użycia lokalnej aplikacji.

## Pierwsze parowanie

1. Na TV zainstaluj Host, skonfiguruj OpenVPN, uruchom serwer i lokalnie włącz usługę dostępności.
2. Na TV wybierz `ROZPOCZNIJ PAROWANIE (2 min)`. Losowy kod 12 znaków (około 60 bitów) działa
   maksymalnie przez 120 sekund, przy maksymalnie pięciu nieudanych próbach.
3. Kontroler Windows/Android TV wprowadza kod, adres VPN hosta i zweryfikowany na ekranie
   telewizora odcisk TLS SHA-256. Tworzy lokalną parę kluczy ECDSA P-256.
4. Komunikat `PAIR <code> <base64url(label)> <base64url(X509_PUBLIC_DER)>` zostaje wysłany
   **dopiero po sprawdzeniu certyfikatu TLS**. Host zwraca `PENDING <random_128bit_id>`.
5. Telewizor pokazuje nazwę kontrolera i SHA-256 jego klucza. Właściciel osobiście wybiera
   `ZATWIERDŹ OCZEKUJĄCY KONTROLER`; bez tego klucz nie jest zaufany.
6. Kontroler odpytuje `PAIR_STATUS <id>` aż otrzyma `PAIRED <key_sha256>` albo odmowę.
7. Klucz publiczny zapisuje się w prywatnych preferencjach aplikacji hosta (max 20 urządzeń).
   Na Windows klucz prywatny jest w pliku użytkownika `~/.android_remote_vpn/controller-identity.pem`
   (na POSIX wymuszony dostęp właściciela); na Android TV klucz prywatny zostaje w Android Keystore.

## Późniejsze logowanie bez kodu

1. TLS 1.2+ z przypiętym certyfikatem hosta.
2. `LOGIN <sha256_of_controller_public_der>` → `CHALLENGE <32B_random_nonce_hex>`.
3. Kontroler podpisuje ECDSA SHA256: `ARDVPN-LOGIN-V1|<HOST_TLS_SHA256>|<CONTROLLER_KEY_ID>|<NONCE>`.
4. `SIGN <base64url(DER_ECDSA_SIGNATURE)>` → `OK 1` albo `ERR BAD_SIGNATURE`.
5. Host sprawdza zaufanie **przy każdej kolejnej komendzie**, dlatego cofnięcie dostępu
   blokuje także już rozpoczętą sesję. Sesja `AUTH <legacy_session_code>` pozostaje dostępna
   dla starszych kontrolerów, lecz sterowanie nadal wymaga odrębnego lokalnego włączenia.

## Cofnięcie dostępu i sytuacje awaryjne

- `COFNIJ DOSTĘP: <nazwa>` na TV trwale usuwa publiczny klucz z listy. Ponowne połączenie
  wymaga całego parowania. Usunięcie jest weryfikowane po zapisie (SharedPreferences.commit).
- `WSTRZYMAJ CAŁY DOSTĘP` blokuje zaufanych i sesyjne sterowanie bez kasowania listy.
- `ZATRZYMAJ SERWER` lub przycisk powiadomienia zatrzymuje nasłuch.
- Cofnięcie systemowych uprawnień dostępności blokuje wykonywanie akcji pilota.
- Uprawnienie MediaProjection NIE jest zapamiętywane przez parowanie: na współczesnych wersjach
  Androida dla nowych sesji przechwytywania potrzebne będą wymagane przez OS zgody.

## Ograniczenia bezpieczeństwa prototypu

- Nie ma jeszcze transmisji obrazu. Wideo nie jest odblokowywane samym parowaniem.
- Host musi zostać uruchomiony lokalnie; brak automatycznego startu po restarcie TV.
- Klucz Windows przechowywany jest lokalnie bez hasła. Dla dystrybucji produkcyjnej warto
  dodać Windows DPAPI/TPM, wersjonowanie i bezpieczną migrację kluczy, blokadę żądań DoS
  i audyt bezpieczeństwa protokołu przed publikacją.
- TV i Windows mogą mieć inne możliwości VPN i dostępności. Zgodność z modelami TV trzeba
  przetestować praktycznie.
- Server nasłuchuje wyłącznie na lokalnym adresie OpenVPN urządzenia hosta. VPN na routerze
  bez klienta na TV wymaga odrębnego, bezpiecznego projektu nasłuchu LAN.
