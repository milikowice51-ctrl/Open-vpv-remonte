package pl.remotevpn.host;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.widget.EditText;
import android.text.InputType;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_IMPORT = 101;
    private static final int REQUEST_NOTIFICATIONS = 102;
    private static final int REQUEST_EMBEDDED_VPN = 301;
    
    private TextView vpnStatus, hostStatus, pairing, controlStatus, pendingInfo, codeInfo, extraSessionBanner;
    private LinearLayout trustedList;
    private EditText vpnUser, vpnPassword;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            java.net.Inet4Address addr = VpnAddress.find(MainActivity.this);
            vpnStatus.setText("VPN: " + EmbeddedVpnService.status + " | " + (addr == null ? "brak tunelu" : "IP " + addr.getHostAddress()));
            hostStatus.setText("Serwer: " + RemoteHostService.status);
            boolean trustedReady = RemoteHostService.running && TrustedControllers.autoAccept(MainActivity.this)
                    && TrustedControllers.pilotEnabled(MainActivity.this)
                    && TvRemoteAccessibilityService.isEnabled()
                    && !TrustedControllers.paused(MainActivity.this);
            controlStatus.setText("Sterowanie TV: "
                    + (TrustedControllers.paused(MainActivity.this) ? "WSTRZYMANE"
                      : trustedReady ? "ZAUFANE AKTYWNE"
                      : RemoteHostService.controlArmed ? "SESJA RĘCZNA AKTYWNA" : "wyłączone")
                    + " | Usługa dostępności: "
                    + (TvRemoteAccessibilityService.isEnabled() ? "aktywna" : "nieaktywna")
                    + " | D-pad: " + (Build.VERSION.SDK_INT >= 33 ? "obsługiwany" : "ograniczony (Android <13)" )
                    + " | Zaufane: " + TrustedControllers.list(MainActivity.this).size()
                    + " | Dostęp wstrzymany: " + (TrustedControllers.paused(MainActivity.this) ? "TAK" : "NIE"));
            if (codeInfo!=null) {
                String code = PairingGate.visibleCode();
                codeInfo.setText(code == null ? "Parowanie nieaktywne / kod wygasł"
                        : "KOD PIERWSZEGO PAROWANIA (120 s): " + code);
            }
            if (extraSessionBanner != null) {
                boolean recentlyUsed = RemoteHostService.lastRemoteCommandAt > 0
                        && System.currentTimeMillis() - RemoteHostService.lastRemoteCommandAt < 60_000L;
                extraSessionBanner.setVisibility(TrustedControllers.extraBanner(MainActivity.this)
                        && recentlyUsed ? View.VISIBLE : View.GONE);
            }
            PairingGate.Pending pending = PairingGate.awaiting();
            pendingInfo.setText(pending == null ? "Brak oczekującego parowania."
                    : "ŻĄDANIE PAROWANIA: " + pending.name + "\nID klucza: " + pending.id);
            if (RemoteHostService.running) {
                pairing.setText("Adres VPN: " + RemoteHostService.address + ":" + RemoteHostService.PORT
                        + "\nKod sesji: " + RemoteHostService.sessionKey
                        + "\nSHA-256 certyfikatu TLS: " + RemoteHostService.fingerprint
                        + "\n\nNie udostępniaj kodu osobom nieupoważnionym.");
            } else pairing.setText("Najpierw połącz VPN, następnie uruchom serwer.\nKod sesji zostanie wygenerowany lokalnie.");
            handler.postDelayed(this, 1500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(18 * getResources().getDisplayMetrics().density);
        page.setPadding(p, p, p, p);
        page.setBackgroundColor(Color.rgb(246, 248, 251));
        scroll.addView(page);
        setContentView(scroll);
        TextView title = text("ANDROID REMOTE VPN — HOST Android / TV v0.5.1", 22, Color.rgb(18, 40, 70));
        page.addView(title);
        page.addView(text("Steruj telewizorem przez własny OpenVPN z kontrolera Windows. Nawigacja D-pad wymaga Android 13+ i jawnego włączenia dostępności. Brak podglądu obrazu.", 14, Color.DKGRAY));
        page.addView(text("1 · KONFIGURACJA VPN", 18, Color.rgb(13, 65, 108)));
        page.addView(button("Importuj plik .ovpn", () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.setType("*/*");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(pick, REQUEST_IMPORT);
        }));
        vpnUser = new EditText(this);
        vpnUser.setHint("Login OpenVPN (opcjonalnie)");
        vpnUser.setSingleLine(true);
        page.addView(vpnUser);
        vpnPassword = new EditText(this);
        vpnPassword.setHint("Hasło OpenVPN (opcjonalnie)");
        vpnPassword.setSingleLine(true);
        vpnPassword.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        page.addView(vpnPassword);
        page.addView(button("Połącz — wbudowany OpenVPN", this::requestEmbeddedVpn));
        page.addView(button("Rozłącz VPN", () -> {
            stopRemoteHost();
            startService(new Intent(this, EmbeddedVpnService.class).setAction(EmbeddedVpnService.ACTION_STOP));
        }));
        vpnStatus = text("VPN: sprawdzanie", 15, Color.BLACK);
        page.addView(vpnStatus);
        page.addView(text("2 · POŁĄCZENIE HOSTA TV", 18, Color.rgb(13, 65, 108)));
        page.addView(button("URUCHOM SERWER", this::requestHostStart));
        page.addView(button("ZATRZYMAJ SERWER", this::stopRemoteHost));
        hostStatus = text("Serwer wyłączony", 15, Color.BLACK);
        page.addView(hostStatus);
        pairing = text("Kod sesji pojawi się po uruchomieniu.", 15, Color.rgb(0, 50, 85));
        pairing.setTextIsSelectable(true);
        page.addView(pairing);
        page.addView(text("3 · STEROWANIE PILOTEM TV Z KOMPUTERA", 18, Color.rgb(13, 65, 108)));
        page.addView(button("Włącz usługę dostępności w ustawieniach", () -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }));
        page.addView(button("ZEZWÓL NA STEROWANIE W TEJ SESJI", this::askToArm));
        page.addView(button("WSTRZYMAJ CAŁY DOSTĘP (także zaufanych)", () -> {
            TrustedControllers.setPaused(this, true);
            RemoteHostService.setControlArmed(false);
            showMessage("Dostęp wstrzymany. Nie będą wykonywane żadne komendy.");
        }));
        page.addView(button("WZNÓW DOSTĘP ZAUFANYCH", () -> new AlertDialog.Builder(this)
                .setTitle("Wznów dostęp")
                .setMessage("Zaufane kontrolery ponownie będą mogły sterować TV, kiedy serwer i usługa dostępności działają.")
                .setNegativeButton("Anuluj", null)
                .setPositiveButton("Wznów", (d, w) -> TrustedControllers.setPaused(this, false)).show()));
        controlStatus = text("Sterowanie TV: wyłączone", 15, Color.BLACK);
        page.addView(controlStatus);
        page.addView(text("4 · ZAUFANE KONTROLERY I ZGODY", 18, Color.rgb(13, 65, 108)));
        extraSessionBanner = text("OSTATNIE STEROWANIE: zdalny kontroler wysłał polecenie w ostatniej minucie.",
                15, Color.rgb(120, 50, 0));
        extraSessionBanner.setVisibility(View.GONE);
        page.addView(extraSessionBanner);
        page.addView(option("Automatycznie akceptuj zaufane kontrolery", TrustedControllers.autoAccept(this),
                checked -> TrustedControllers.setAutoAccept(this, checked)));
        page.addView(option("Zezwalaj na sterowanie pilotem", TrustedControllers.pilotEnabled(this),
                checked -> TrustedControllers.setPilotEnabled(this, checked)));
        page.addView(option("Dodatkowy baner sesji (nie powiadomienie systemowe)", TrustedControllers.extraBanner(this),
                checked -> TrustedControllers.setExtraBanner(this, checked)));
        page.addView(text("Dodatkowy baner pokazuje informację na tej stronie po zdalnym poleceniu, gdy jest włączony. Powiadomienie hosta z przyciskiem ZATRZYMAJ pozostaje widoczne niezależnie od ustawienia.", 13, Color.DKGRAY));
        page.addView(button("ROZPOCZNIJ PAROWANIE (2 min)", () -> {
            if (!RemoteHostService.running || TrustedControllers.paused(this)) {
                showMessage("Uruchom serwer i wznów dostęp przed parowaniem."); return;
            }
            String code = PairingGate.begin();
            codeInfo.setText("KOD PIERWSZEGO PAROWANIA (120 s): " + code);
            showMessage("Kod przepisz na swoim komputerze. Potem zatwierdź jego klucz na TV.");
        }));
        codeInfo = text("Parowanie nieaktywne", 15, Color.rgb(0,50,85));
        codeInfo.setTextIsSelectable(true);
        page.addView(codeInfo);
        pendingInfo = text("Brak oczekującego parowania.", 15, Color.BLACK);
        page.addView(pendingInfo);
        page.addView(button("ZATWIERDŹ OCZEKUJĄCY KONTROLER", this::approvePending));
        page.addView(button("ODRZUĆ PAROWANIE", () -> {
            PairingGate.deny(); showMessage("Prośba o parowanie odrzucona.");
        }));
        page.addView(text("5 · USUWANIE ZAUFANIA", 18, Color.rgb(13, 65, 108)));
        page.addView(button("Odśwież listę zaufanych kontrolerów", this::refreshDevices));
        trustedList = new LinearLayout(this);
        trustedList.setOrientation(LinearLayout.VERTICAL);
        page.addView(trustedList);
        refreshDevices();
        page.addView(text("Zainstaluj OpenVPN for Android (de.blinkt.openvpn), o ile ten klient VPN działa na Twoim TV. Profil musi zawierać osadzone certyfikaty. Sterowanie ekranem nie oznacza podglądu obrazu; wideo to kolejny etap.", 13, Color.DKGRAY));
        page.addView(button("Otwórz ustawienia aplikacji", () -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }));
    }
    private void askToArm() {
        if (!RemoteHostService.running) {
            showMessage("Najpierw uruchom serwer w aktywnym VPN."); return;
        }
        if (!TvRemoteAccessibilityService.isEnabled()) {
            showMessage("Najpierw włącz usługę dostępności w ustawieniach TV."); return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Zgoda na zdalne sterowanie")
                .setMessage("Po zatwierdzeniu sparowany kontroler z poprawnym kodem sesji będzie mógł nawigować po TV (Home, Wstecz, kierunki i OK). Zatrzymasz dostęp przyciskiem WSTRZYMAJ CAŁY DOSTĘP lub w powiadomieniu. W tej wersji nie będzie podglądu ekranu.")
                .setNegativeButton("Anuluj", null)
                .setPositiveButton("Włącz sterowanie", (dialog, which) -> {
                    RemoteHostService.setControlArmed(true);
                    showMessage(RemoteHostService.controlArmed ?
                            "Sterowanie włączone dla bieżącej sesji." : "Sterowanie nie zostało włączone.");
                }).show();
    }

    private interface CheckedCallback { void set(boolean checked); }
    private CheckBox option(String description, boolean value, CheckedCallback callback) {
        CheckBox box = new CheckBox(this);
        box.setText(description);
        box.setTextColor(Color.BLACK);
        box.setTextSize(15);
        box.setChecked(value);
        box.setOnCheckedChangeListener((button, checked) -> callback.set(checked));
        return box;
    }
    private void approvePending() {
        PairingGate.Pending pending = PairingGate.awaiting();
        if (pending == null) { showMessage("Brak aktualnej prośby o parowanie."); return; }
        new AlertDialog.Builder(this)
                .setTitle("Zaufaj temu kontrolerowi?")
                .setMessage("Nazwa: " + pending.name + "\nSHA-256 klucza: " + pending.id
                        + "\n\nPo zatwierdzeniu urządzenie może ponownie łączyć się bez kodu, aż cofniesz zaufanie. Nie udziela to zgody na nagrywanie obrazu.")
                .setNegativeButton("Odrzuć", (d,w)->PairingGate.deny())
                .setPositiveButton("DODAJ DO ZAUFANYCH", (d,w)-> {
                    if (PairingGate.approve(this)) {
                        showMessage("Zapisano zaufany kontroler."); refreshDevices();
                    } else showMessage("Nie zapisano klucza. Sprawdź listę i spróbuj ponownie.");
                }).show();
    }
    private void refreshDevices() {
        if (trustedList == null) return;
        trustedList.removeAllViews();
        java.util.List<TrustedControllers.Device> devices=TrustedControllers.list(this);
        if (devices.isEmpty()) trustedList.addView(text("Brak sparowanych kontrolerów",14,Color.DKGRAY));
        for (TrustedControllers.Device device:devices) {
            trustedList.addView(text(device.name + "\n" + device.id, 13, Color.BLACK));
            trustedList.addView(button("COFNIJ DOSTĘP: " + device.name, () ->
                    new AlertDialog.Builder(this)
                        .setTitle("Cofnij zaufanie")
                        .setMessage("Usunąć klucz urządzenia " + device.name
                                + "? Już otwarte połączenia zostaną zablokowane przy następnej komendzie.")
                        .setNegativeButton("Anuluj", null)
                        .setPositiveButton("Cofnij dostęp", (d,w) -> {
                            if (TrustedControllers.revoke(this,device.id)) {
                                showMessage("Usunięto zaufanie. Aby powrócić, konieczne nowe parowanie.");
                                refreshDevices();
                            } else showMessage("Nie udało się zapisać usunięcia.");
                        }).show()));
        }
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(sp); view.setTextColor(color);
        view.setPadding(0, 12, 0, 16);
        return view;
    }
    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        return b;
    }
    private void showMessage(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
    }
    @Override protected void onStart() {
        super.onStart(); handler.post(refresh);
    }
    @Override protected void onStop() {
        handler.removeCallbacks(refresh); super.onStop();
    }
    private void requestHostStart() {
        if (VpnAddress.find(this) == null) {
            showMessage("Brak aktywnego OpenVPN. Połącz VPN przed uruchomieniem serwera."); return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        } else startRemoteHost();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQUEST_NOTIFICATIONS) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startRemoteHost();
            else showMessage("Dla widocznej sesji wymagamy zezwolenia na powiadomienia.");
        }
    }
    private void startRemoteHost() {
        Intent intent = new Intent(this, RemoteHostService.class).setAction(RemoteHostService.ACTION_START);
        try { startForegroundService(intent); }
        catch (Exception e) { showMessage("Nie udało się uruchomić: " + e.getMessage()); }
    }
    private void stopRemoteHost() {
        Intent stop = new Intent(this, RemoteHostService.class).setAction(RemoteHostService.ACTION_STOP);
        startService(stop);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_IMPORT) {
            if (result == RESULT_OK && data != null && data.getData() != null) importOvpn(data.getData());
            return;
        }
        if (request == REQUEST_EMBEDDED_VPN && result == RESULT_OK) startEmbeddedVpn();
    }
    private void requestEmbeddedVpn() {
        try {
            OvpnProfilePolicy.validate(SecureProfileStore.load(this));
            Intent permission = VpnService.prepare(this);
            if (permission != null) startActivityForResult(permission, REQUEST_EMBEDDED_VPN);
            else startEmbeddedVpn();
        } catch (Exception error) {
            showMessage("Zaimportuj poprawny plik .ovpn: " + error.getMessage());
        }
    }
    private void startEmbeddedVpn() {
        Intent start = new Intent(this, EmbeddedVpnService.class).setAction(EmbeddedVpnService.ACTION_CONNECT);
        start.putExtra("username", vpnUser.getText().toString());
        start.putExtra("password", vpnPassword.getText().toString());
        vpnPassword.setText("");
        try { startForegroundService(start); }
        catch (Exception error) { showMessage("Nie można uruchomić VPN: " + error.getMessage()); }
    }
    private void importOvpn(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("Nie można odczytać pliku.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int size = 0, read;
            while ((read = input.read(buf)) != -1) {
                size += read;
                if (size > 256 * 1024) throw new IllegalArgumentException("Plik ma ponad 256 KiB.");
                out.write(buf, 0, read);
            }
            String config = out.toString(StandardCharsets.UTF_8.name());
            boolean remoteFound = false;
            boolean clientFound = false;
            // Nie akceptujemy odwołań do zewnętrznych plików PEM w tym prototypie.
            for (String line : config.split("\\R")) {
                String clean = line.trim();
                if (clean.isEmpty() || clean.startsWith("#") || clean.startsWith(";")) continue;
                if (clean.matches("(?i)^remote\\s+.*")) remoteFound = true;
                if (clean.equalsIgnoreCase("client") || clean.equalsIgnoreCase("tls-client")) clientFound = true;
                if (clean.matches("(?i)^(ca|cert|key|tls-auth|tls-crypt|pkcs12|secret)\\s+.+"))
                    throw new IllegalArgumentException("Profil odwołuje się do osobnych plików certyfikatów/kluczy. Osadź je w .ovpn.");
            }
            if (!remoteFound) throw new IllegalArgumentException("Brakuje dyrektywy remote.");
            if (!clientFound) throw new IllegalArgumentException("Brakuje dyrektywy client/tls-client.");
            OvpnProfilePolicy.validate(config);
            SecureProfileStore.save(this, config);
            showMessage("Zaimportowano i zaszyfrowano profil .ovpn. Uruchom połączenie.");
        } catch (Exception e) { showMessage("Import nieudany: " + e.getMessage()); }
    }
}
