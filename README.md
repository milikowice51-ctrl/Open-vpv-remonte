# Android Remote VPN v0.5.1 — wbudowany OpenVPN 3 Core (kandydat źródłowy)

**Status, 10 listopada 2026 — poprawka 0.5.1:** zrealizowano kod integracji Android `VpnService` ↔ JNI ↔ prawdziwy silnik OpenVPN 3 Core oraz GUI do importu `.ovpn` i wprowadzania loginu i hasła. **Nie ma jeszcze skompilowanej ani przetestowanej APK v0.5.** Próba kompilacji wykazała brak Gradle, SDK 35 i możliwości pobrania repozytorium OpenVPN 3 z powodu błędu DNS. Poprawiono wymaganą metodę `pause_on_connection_timeout()` z aktualnego API OpenVPN 3 Core i wzmocniono test JNI przeciwko brakującym metodom abstrakcyjnym. Kod JNI i proces budowania trzeba sprawdzić i ewentualnie poprawić na komputerze z Android Studio. To **nie** jest gotowy instalator.

## Co zmieniłem względem v0.4

- Moduł Android Host (`android-host/app`) nie wymaga już `OpenVPN for Android`. Usunięto poprzedni zewnętrzny `OpenVpnBridge`/AIDL.
- Aplikacja sama prosi Android o `VpnService.prepare()` i oferuje przyciski **Połącz — wbudowany OpenVPN** i **Rozłącz VPN**.
- `EmbeddedVpnService` zarządza TUN, trasami, DNS, MTU, chroni gniazda OpenVPN przed ponownym skierowaniem do tunelu, pokazuje stałe powiadomienie z przyciskiem rozłączenia i reaguje na cofnięcie zgody.
- `NativeOpenVpn` i `native_openvpn.cpp` wykonują połączenia z oficjalnym `OpenVPNClient` z OpenVPN 3 Core; nie udają połączenia przez samą implementację VpnService.
- Użytkownik może wpisać login i hasło (nie są trwale przechowywane). Certyfikaty i klucze powinny być zawarte inline w `.ovpn`. Konfiguracja jest zaszyfrowana AES-GCM w Android Keystore.
- Dotychczasowy mechanizm parowania zaufanych kontrolerów, serwer TLS i sterowanie pilotem pozostają w projekcie.
- Obraz zdalnego urządzenia **nadal nie jest zaimplementowany**.

**Zakres tej zmiany:** VPN w `android-host/app` na telefonach i Android TV (ABI `arm64-v8a`). `tv-controller` jest nadal na starej zewnętrznej integracji i wymaga osobnej migracji, tak jak kontroler Windows. Nie przedstawiaj tego jako gotowej jednej APK dla wszystkich urządzeń.

## Zależności i wymogi kompilacji

- JDK 17, Android Studio, Android SDK 35, Android NDK `27.2.12479018`, CMake `3.22.1`, Gradle `8.9`, Git, Bash i narzędzia kompilacji C++.
- `OpenVPN/openvpn3` (oficjalna biblioteka C++), moduły źródłowe `client/ovpncli.cpp` i `openvpn/crypto/data_epoch.cpp`. Pobieranie następuje dopiero na komputerze deweloperskim.
- Biblioteki dla Android `arm64-android`: OpenSSL, asio, lz4, fmt, xxhash i jsoncpp, instalowane przez vcpkg. Weryfikacja kompilacji może ujawnić konieczność zmian w wersjach bibliotek lub CMake. Nie publikuj APK przed testem pełnego handshake i routingu.
- Kod OpenVPN3 jest udostępniany na zasadach MPL-2.0 lub AGPLv3, zgodnie z upstream. Sprawdź i dołącz powiadomienia licencyjne oraz wymagane źródła przed dystrybucją. https://github.com/OpenVPN/openvpn3

### Pobranie silnika i zależności (Linux/macOS lub Git Bash)

```bash
cd android-remote-vpn-v0.5
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/27.2.12479018"  # DOSTOSUJ
bash native/setup-upstream.sh
bash native/setup-deps.sh
export VCPKG_ROOT="$(pwd)/native/vcpkg"
cd android-host
gradle :app:assembleDebug
```

Wynik **dopiero po udanym kompilowaniu**: `android-host/app/build/outputs/apk/debug/app-debug.apk`. Brak `gradle-wrapper.jar` w paczce — użyj Gradle z Android Studio lub lokalnej instalacji.

### Pierwszy test po zbudowaniu

1. Zainstaluj aplikację Host APK na **własnym testowym telefonie**. Nie instaluj żadnej dodatkowej aplikacji OpenVPN.
2. Importuj `.ovpn` zawierający certyfikaty inline; wpisz login/hasło, jeśli serwer ich wymaga.
3. Naciśnij **Połącz — wbudowany OpenVPN** i zaakceptuj **systemowe** pytanie o połączenie VPN. Powinna pojawić się ikona klucza VPN.
4. Zweryfikuj IP tunelu i **dostęp do własnej podsieci** (test ping wykonany z komputera po VPN); nie traktuj samej ikony jako dowodu pełnego działania.
5. Uruchom serwer Host TLS i przetestuj połączenie oraz później parowanie przy użyciu `controller/controller.py`.
6. Przetestuj **Rozłącz VPN**, utratę Wi‑Fi, zmianę sieci i cofnięcie zgody systemowej. Serwer zdalnego sterowania musi wyłączyć się po utracie VPN.

### Ograniczenia tego etapu

- Nieweryfikowane na prawdziwym urządzeniu, więc mogą wystąpić błędy kompilacji natywnej lub problemy integracyjne.
- Nieobsługiwane profile z osobnymi plikami CA/cert/key, zewnętrznym PKI/PKCS#11, zewnętrznymi skryptami, autoryzacją SSO/MFA, DoH/DoT, niestandardowym portem DNS lub nietypowymi rozszerzeniami.
- Routery i sieci z nietypowym MTU, IPv6 lub wykluczaniem tras wymagają osobnych testów; wykluczanie tras wymaga Android 13+.
- Brak automatycznego wznowienia po restarcie na tym etapie; przywrócenie zgody nie pomija ustawień systemowych.
- API VpnService nie zawiera silnika OpenVPN. Potrzebna jest faktycznie zbudowana biblioteka `libremoteopenvpn.so` oraz jej zależności, którą tworzy nasz projekt w procesie kompilacji.
- Nie dodano strumieniowania obrazu; zachowano wymagane zgody Androida, trwałą autoryzację i możliwość cofnięcia dostępu.

### Testy dostępne bez Android SDK

```bash
bash tests/run_policy_tests.sh
cd controller && python -m unittest discover -p 'test_*.py' -v
```

Testy polityki profili i protokołu **nie** dowodzą, że natywny silnik łączy się z serwerem OpenVPN.


## Próba kompilacji i dalsze kroki v0.5.1

Przeprowadzono próbę `gradle :app:assembleDebug` — środowisko odpowiedziało `gradle: command not found`; `git ls-remote` zwrócił `Could not resolve host: github.com`, a Android SDK 35 nie jest zainstalowany. **APK nie powstała**. Instrukcja uruchomienia prawdziwej kompilacji na GitHub Actions lub Linux/WSL2 znajduje się w [BUILD_APK_GITHUB.md](BUILD_APK_GITHUB.md).
