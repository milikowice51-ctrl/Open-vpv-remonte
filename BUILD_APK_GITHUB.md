# Budowanie instalatora APK — Android Remote VPN v0.5.1

**Uwaga:** ten pakiet to kod źródłowy, a nie skompilowana APK. Build wymaga pobrania prawdziwego OpenVPN 3 Core, Android SDK 35, Android NDK 27.2.12479018, Gradle 8.9 i zależności natywnych. Nie uruchamiaj „fałszywych APK” utworzonych przez samo spakowanie plików.

## Najłatwiejsza opcja: GitHub Actions

1. Utwórz **prywatne repozytorium** na GitHub (nie umieszczaj w nim swojego pliku `.ovpn`, certyfikatów ani haseł!).
2. Rozpakuj `android-remote-vpn-v0.5.1-source.zip` i prześlij **zawartość** katalogu, tak żeby `.github/workflows/android-host-debug.yml`, `android-host/`, `native/` były w katalogu głównym repozytorium.
3. Otwórz zakładkę **Actions** i zezwól na uruchamianie workflow, jeżeli GitHub o to poprosi.
4. Wybierz **Android Host native OpenVPN3 prototype**, następnie **Run workflow** na gałęzi głównej.
5. Otwórz szczegóły uruchomienia: zielony wynik oznacza pomyślną kompilację; czerwony oznacza błąd do poprawienia w kodzie/zależnościach. NIE traktuj samego uruchomienia jako potwierdzenia działania VPN.
6. Tylko przy sukcesie pobierz **android-remote-vpn-v0.5.1-host-debug** z sekcji **Artifacts**. W archiwum będzie `app-debug.apk`, do zainstalowania na testowym telefonie z procesorem ARM64.
7. W razie błędu skopiuj log z kroku **Build one Host APK (arm64-v8a)** lub poprzedniego, który nie przeszedł, bez prywatnych danych.

Uwaga: nie mogłem uruchomić GitHub Actions w Twoim repozytorium z tej sesji. To recepta na rzeczywisty, powtarzalny build, nie raport o ukończonym buildzie.

## Linux / WSL2

Zainstaluj Android SDK 35, NDK `27.2.12479018`, CMake `3.22.1` oraz Gradle `8.9`; wymagane jest połączenie z internetem, aby pobrać źródła OpenVPN 3 i zależności. Następnie:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
bash scripts/build-host-linux.sh
```

Skrypt kończy się błędem, jeśli SDK/NDK/Gradle nie jest dostępny albo nie powstał poprawny plik APK. Po sukcesie gotowy instalator debug będzie w `dist/`.

## Zakres pierwszego testu

Wersja hosta v0.5.1 ma połączyć własny `VpnService` z OpenVPN 3, bez osobnej aplikacji OpenVPN. Po instalacji importuj testowy profil `.ovpn` z certyfikatami inline i przejdź przez systemową zgodę VPN. Sprawdź rzeczywiste zestawienie tunelu i ruch sieciowy. Następnie testuj serwer TLS i autoryzację. Podgląd obrazu i zdalny pulpit NIE są jeszcze zbudowane. **Nie udostępniaj na tym etapie APK osobom trzecim**; projekt nie przeszedł audytu bezpieczeństwa ani testów urządzeń.
