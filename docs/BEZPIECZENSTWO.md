## Rozszerzenie v0.4

Parowanie wymaga 12-znakowego kodu o ograniczonym czasie działania i potwierdzenia
przez właściciela na ekranie TV. Trwałe uprawnienia są związane z publicznym kluczem
ECDSA P-256 kontrolera, nie IP. Wymiana działa tylko po zweryfikowaniu certyfikatu TLS.
Cofnięcie zaufania usuwa klucz publiczny; serwer sprawdza stan przed każdą komendą.
Wstrzymanie dostępu blokuje wszystkie komendy bez kasowania listy.

Wyłączenie `extra_banner` dotyczy tylko dodatkowego elementu interfejsu, nigdy
systemowego powiadomienia ani wymaganego zezwolenia MediaProjection.

Zob. [`PAROWANIE.md`](PAROWANIE.md).

---

# Prywatność, zgody i granice prototypu

## Bezpieczeństwo etapu 1

- Profil OpenVPN może zawierać prywatny klucz urządzenia. **Nie wysyłaj go nikomu**, także do autorów aplikacji. Import zapisuje plik w wewnętrznej pamięci aplikacji, zaszyfrowany AES-256-GCM kluczem Android Keystore. Plik nie jest dołączony do backupu Androida.
- Wersja demonstracyjna korzysta z zewnętrznej aplikacji OpenVPN for Android. Właściciel osobno zatwierdza uprawnienia do jej sterowania i zezwolenie Androida na uruchomienie VPN.
- Serwer nasłuchuje tylko na adresie IPv4 VPN. Nie ma UPnP, publicznego przekierowania portów ani serwera pośredniczącego.
- Komunikacja odbywa się przez TLS 1.2+, z certyfikatem tworzonym przez Android Keystore. Kontroler weryfikuje dokładny odcisk certyfikatu przed wysłaniem klucza sesji.
- Klucz sesji jest losowy, 128-bitowy i działa tylko do zatrzymania serwera. Po restarcie zmienia się.
- Powiadomienie Androida pokazuje aktywny serwer i zapewnia natychmiastowe zatrzymanie.
- Do protokołu na tym etapie nie dodano żadnej komendy wykonującej gest, ekran, odczyt plików czy uruchomienie aplikacji.

## Ograniczenia i ryzyka

- Samo posiadanie `.ovpn` nie zapewnia routingu pomiędzy klientami. Administrator OpenVPN musi zezwolić na właściwe trasy/firewall oraz komunikację uczestników.
- Osoba z kluczem sesji, odciskiem i dostępem sieciowym do urządzenia może wykonywać dozwolone operacje protokołu. Nie publikuj tych danych w Internecie.
- TLS chroni treść komunikacji przed pośrednikami, ale pierwszy odcisk certyfikatu trzeba sprawdzić niezależnym zaufanym kanałem (np. osobiście), żeby uniknąć MITM podczas parowania.
- Nie wdrożono mechanizmu blokowania po wielu błędnych próbach; sekrety są długie, ale test odporności na DoS jest wymagany.
- v0.1 nie uwzględnia automatycznego zarządzania energią (oszczędzanie baterii może zatrzymać aplikację w niestandardowych nakładkach producentów).
- To jest **prototyp do testów we własnym środowisku**, a nie zweryfikowany produkt do zdalnego zarządzania osobami trzecimi.

## Wymagania etapu zdalnego ekranu / sterowania

- Android MediaProjection: właściciel zatwierdza każdy start sesji udostępniania ekranu; foregroundService typu `mediaProjection` i widoczne powiadomienie.
- Android AccessibilityService: użytkownik sam otwiera Ustawienia i jawnie włącza usługę; prezentujemy zakres dostępu. Nie próbujemy ukrywać śladów działania ani obchodzić systemowych zgód.
- Odrębne przełączniki zgody na podgląd i sterowanie. Nie udostępniamy samoczynnego ani niejawnego podglądu ekranu.
- Będziemy respektować aplikacje blokujące przechwytywanie ekranu (`FLAG_SECURE`).
- Android 15+ wprowadza ograniczenia uruchamiania foreground-service `mediaProjection` po `BOOT_COMPLETED`. Nie deklarujemy autostartu przechwytywania po restarcie.

## Publiczne źródła API

- https://developer.android.com/media/grow/media-projection
- https://developer.android.com/guide/topics/ui/accessibility/service
- https://developer.android.com/develop/connectivity/vpn
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://github.com/schwabe/ics-openvpn/tree/master/remoteExample

## Kontroler Android TV — v0.2

- Kontroler TV **nie** otwiera portu nasłuchującego: łączy się wychodząco
  z ręcznie wskazanym hostem Android przez istniejącą trasę VPN.
- Wpisane wartości: host/port/kod sesji/odcisk TLS pozostają w pamięci aktywności;
  nie są zapisywane w preferencjach ani w logach. Okno ma flagę `FLAG_SECURE`.
- Profil `.ovpn` jest zapisany AES-GCM/Android Keystore i nie jest objęty backupem.
- Szyfrowanie TLS jest wymagane; dokładna zgodność SHA-256 certyfikatu jest
  weryfikowana przed wysłaniem klucza sesji. Nie zaleca się przesyłania
  odcisku i kodu tym samym niezaufanym kanałem.
- Dostępne polecenia protokołu v1 to tylko `PING`, `INFO`, `QUIT`.
- Zewnętrzny OpenVPN for Android ma oddzielny zestaw zgód i może nie działać
  na niektórych telewizorach. Użytkownik zatwierdza wszystkie prośby VPN.


## Uzupełnienie bezpieczeństwa dla v0.3 (host TV)

- Zdalny pilot ma jawny przełącznik `ZEZWÓL NA STEROWANIE W TEJ SESJI`
  zatwierdzany lokalnie na TV; nigdy nie jest włączany poleceniem sieciowym.
- Osobna usługa dostępności wymaga aktywacji przez właściciela w ustawieniach.
  Jest ograniczona do globalnych działań nawigacyjnych; nie zbiera tekstu
  z okien, haseł ani wydarzeń klawiatury.
- Każde połączenie weryfikuje odcisk certyfikatu **przed wysłaniem** kodu sesji.
  Kod jest świeży po restarcie hosta i nie jest zapisywany przez kontrolery.
- Host binduje port 48376 wyłącznie na lokalnym adresie IP interfejsu VPN;
  utrata VPN kończy sesję. Wrażliwe informacje w aktywności hosta są objęte
  `FLAG_SECURE`.
- To prototyp do testów na zaufanej sieci: nie ma jeszcze ochrony przed
  wyczerpaniem zasobów (DoS), trwałego parowania urządzeń, rotacji
  certyfikatów, dziennika uprawnień ani audytu wymaganego do publikacji.
- Brak obrazu, dźwięku i zdalnych gestów dotykowych w tej wersji.
