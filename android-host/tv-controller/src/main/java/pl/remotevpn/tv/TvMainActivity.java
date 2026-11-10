package pl.remotevpn.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** D-pad compatible Android TV client for stage v0.2 (VPN + TLS diagnostics). */
public final class TvMainActivity extends Activity {
    private static final int REQUEST_IMPORT = 301;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText hostField, portField, secretField, fingerprintField, pairingField;
    private CheckBox trustedMode;
    private TextView statusField, vpnField;
    private Button testButton;
    private OpenVpnBridge openVpn;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        openVpn = new OpenVpnBridge(this, this::message);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(15, 25, 40));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(28);
        content.setPadding(padding, dp(15), padding, dp(25));
        scroll.addView(content);
        setContentView(scroll);

        content.addView(label("ANDROID REMOTE VPN — KONTROLER TV", 25, 0xFF9ED6FF));
        content.addView(label("Etap 0.4 • Trwałe parowanie i diagnostyka oraz pilot innego TV (z jego zgodą). Bez podglądu obrazu", 16, Color.WHITE));
        content.addView(label("1. WŁASNY OPENVPN (OPCJONALNIE)", 19, 0xFF94C5FF));
        content.addView(label("Jeśli telewizor ma trasę VPN przez router, pomiń ten krok. Inaczej użyj OpenVPN for Android — jeżeli działa na Twoim modelu TV.", 14, 0xFFD5DFEA));
        content.addView(button("Importuj profil .ovpn", () -> {
            Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            open.addCategory(Intent.CATEGORY_OPENABLE);
            open.setType("*/*");
            startActivityForResult(open, REQUEST_IMPORT);
        }));
        content.addView(button("Połącz OpenVPN (aplikacja zewnętrzna)", () -> {
            try { openVpn.start(SecureProfileStore.load(this)); }
            catch (Exception e) { message("Najpierw zaimportuj profil .ovpn."); }
        }));
        content.addView(button("Rozłącz OpenVPN", () -> openVpn.disconnect()));
        vpnField = label("Połączenie VPN: sprawdź trasę do hosta", 14, 0xFFD5DFEA);
        content.addView(vpnField);
        content.addView(label("2. POŁĄCZ Z TELEFONEM PRZEZ VPN", 19, 0xFF94C5FF));
        hostField = field(content, "Adres VPN urządzenia (np. 10.8.0.6)", "", false, false);
        portField = field(content, "Port", "48376", false, true);
        secretField = field(content, "Kod sesji hosta (32 znaki HEX)", "", true, false);
        fingerprintField = field(content, "SHA-256 certyfikatu hosta (64 znaki HEX)", "", false, false);
        pairingField = field(content, "Kod parowania (12 znaków, po włączeniu na Hoście)", "", true, false);
        content.addView(button("PARUJ TEN KONTROLER Z HOSTEM", this::pairWithHost));
        trustedMode = new CheckBox(this);
        trustedMode.setText("Użyj sparowanego klucza (bez kodu sesji)");
        trustedMode.setTextColor(Color.WHITE);
        trustedMode.setChecked(true);
        content.addView(trustedMode);
        testButton = button("TESTUJ BEZPIECZNE POŁĄCZENIE", this::testConnection);
        content.addView(testButton);
        content.addView(label("3. PILOT DLA ZDALNEGO ANDROID TV", 19, 0xFF94C5FF));
        content.addView(label("TV host musi jawnie włączyć sterowanie i usługę dostępności. Wybierz przycisk pilotem, a następnie wciśnij OK.", 14, Color.WHITE));
        addRemotePad(content);
        statusField = label("Oczekiwanie na dane. Wpisz kod i SHA-256 od właściciela telefonu.", 17, Color.WHITE);
        statusField.setFocusable(false);
        statusField.setTextIsSelectable(false);
        content.addView(statusField);
        content.addView(label("W trybie zaufanym klucz podpisu pozostaje w Android Keystore. Usunięcie zgody wykonaj na hoście. Powiadomienia systemowe pozostają aktywne.", 14, 0xFFBCCFE0));
        hostField.requestFocus();
    }

    private EditText field(LinearLayout layout, String hint, String initial, boolean secret, boolean numeric) {
        TextView heading = label(hint, 15, 0xFFD5DFEA);
        layout.addView(heading);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFFA7BACB);
        input.setTextSize(18);
        input.setHint(hint);
        input.setPadding(dp(12), dp(7), dp(12), dp(8));
        input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF8EC9FF));
        input.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        if (secret) input.setTransformationMethod(PasswordTransformationMethod.getInstance());
        input.setText(initial);
        input.setSelectAllOnFocus(true);
        layout.addView(input);
        return input;
    }

    private void addRemotePad(LinearLayout content) {
        String[][] commands = {
                {"HOME", "UP", "BACK"},
                {"LEFT", "OK", "RIGHT"},
                {"", "DOWN", ""}
        };
        for (String[] row : commands) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            content.addView(line);
            for (String command : row) {
                if (command.isEmpty()) {
                    android.view.View spacer = new android.view.View(this);
                    line.addView(spacer, new LinearLayout.LayoutParams(0, dp(56), 1));
                } else {
                    Button b = button(command, () -> remoteKey(command));
                    line.addView(b, new LinearLayout.LayoutParams(0, dp(56), 1));
                }
            }
        }
    }

    private void remoteKey(String key) {
        String host = hostField.getText().toString().trim();
        String secret = secretField.getText().toString().trim();
        String fingerprint = fingerprintField.getText().toString().trim();
        final int port;
        try { port = Integer.parseInt(portField.getText().toString().trim()); }
        catch (NumberFormatException error) { message("Błędny numer portu"); return; }
        final boolean useTrusted = trustedMode.isChecked();
        statusField.setText("Wysyłanie: " + key);
        executor.execute(() -> {
            String output;
            try {
                output = useTrusted
                        ? TrustedTvClient.sendKey(host, port, fingerprint, key)
                        : TlsProbeClient.sendTvKey(host, port, secret, fingerprint, key);
            } catch (Exception error) {
                output = "TV odmówił: " + error.getMessage();
            }
            final String value = output;
            runOnUiThread(() -> statusField.setText(value));
        });
    }

    private void pairWithHost() {
        String host=hostField.getText().toString().trim();
        String fp=fingerprintField.getText().toString().trim();
        String code=pairingField.getText().toString().trim();
        final int port;
        try { port=Integer.parseInt(portField.getText().toString().trim()); }
        catch (Exception e) { message("Błędny port"); return; }
        statusField.setText("Parowanie… zatwierdź ten telewizor na zdalnym hoście.");
        executor.execute(() -> {
            String result;
            try {
                String id=TrustedTvClient.pair(host,port,fp,code,"Android TV kontroler");
                result="SPAROWANO Z HOSTEM\n"+id;
            } catch (Exception e) { result="Parowanie nieudane: "+e.getMessage(); }
            String finished=result;
            runOnUiThread(() -> {
                statusField.setText(finished);
                if (finished.startsWith("SPAROWANO")) trustedMode.setChecked(true);
            });
        });
    }

    private void testConnection() {
        String host = hostField.getText().toString().trim();
        String port = portField.getText().toString().trim();
        String key = secretField.getText().toString().trim();
        String fingerprint = fingerprintField.getText().toString().trim();
        final int portValue;
        try { portValue = Integer.parseInt(port); }
        catch (NumberFormatException e) { message("Błędny numer portu"); return; }
        final boolean useTrusted = trustedMode.isChecked();
        testButton.setEnabled(false);
        statusField.setText("Łączenie… weryfikacja certyfikatu i autoryzacji.");
        executor.execute(() -> {
            final String output;
            try {
                TlsProbeClient.Result result = useTrusted
                        ? TrustedTvClient.probe(host, portValue, fingerprint)
                        : TlsProbeClient.probe(host, portValue, key, fingerprint);
                JSONObject info = new JSONObject(result.jsonInfo);
                output = "POŁĄCZENIE POTWIERDZONE ✓\n"
                        + "Urządzenie: " + info.optString("device", "?")
                        + "\nAndroid: " + info.optString("android", "?")
                        + "\nIP VPN hosta: " + info.optString("vpnAddress", "?")
                        + "\nPing: " + result.pingMillis + " ms"
                        + "\nProtokół: " + info.optString("version", "?")
                        + "\nPodgląd obrazu: NIEDOSTĘPNY; nawigacja pilotem: po włączeniu zgód na hoście";
            } catch (Exception e) {
                output = "BŁĄD POŁĄCZENIA: " + e.getClass().getSimpleName()
                        + ": " + (e.getMessage() == null ? "Brak szczegółów" : e.getMessage());
            }
            runOnUiThread(() -> {
                statusField.setText(output);
                testButton.setEnabled(true);
            });
        });
    }

    @Override protected void onStart() {
        super.onStart();
        openVpn.bind();
        vpnField.setText("VPN w aplikacji: " +
                (VpnAddress.find(this) == null ? "brak wykrytego adresu interfejsu VPN (możliwa trasa przez router)"
                        : "aktywny interfejs VPN " + VpnAddress.find(this).getHostAddress()));
    }

    @Override protected void onStop() {
        openVpn.unbind();
        super.onStop();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_IMPORT) {
            if (result == RESULT_OK && data != null && data.getData() != null) importProfile(data.getData());
        } else if (request == OpenVpnBridge.REQUEST_API || request == OpenVpnBridge.REQUEST_VPN) {
            openVpn.onPermissionResult(request, result);
        }
    }

    private void importProfile(Uri uri) {
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IllegalArgumentException("Plik jest niedostępny");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int len, count = 0;
            while ((len = stream.read(buffer)) != -1) {
                count += len;
                if (count > 256 * 1024) throw new IllegalArgumentException("Profil przekracza 256 KiB");
                bytes.write(buffer, 0, len);
            }
            String config = bytes.toString(StandardCharsets.UTF_8.name());
            boolean remote = false, client = false;
            for (String line : config.split("\\R")) {
                String cleaned = line.trim();
                if (cleaned.isEmpty() || cleaned.startsWith("#") || cleaned.startsWith(";")) continue;
                if (cleaned.matches("(?i)^remote\\s+.*")) remote = true;
                if (cleaned.equalsIgnoreCase("client") || cleaned.equalsIgnoreCase("tls-client")) client = true;
                if (cleaned.matches("(?i)^(ca|cert|key|tls-auth|tls-crypt|pkcs12|secret)\\s+.+"))
                    throw new IllegalArgumentException("Profil wymaga osobnych plików PEM. Użyj konfiguracji inline.");
            }
            if (!remote || !client) throw new IllegalArgumentException("Brak 'client' lub 'remote' w .ovpn");
            SecureProfileStore.save(this, config);
            message("Zapisano zaszyfrowany profil .ovpn. Możesz połączyć VPN.");
        } catch (Exception e) { message("Import: " + e.getMessage()); }
    }

    private TextView label(String text, int size, int color) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setGravity(Gravity.START);
        label.setPadding(0, dp(7), 0, dp(7));
        return label;
    }

    private Button button(String text, Runnable click) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setMinHeight(dp(52));
        button.setFocusable(true);
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void message(String info) {
        android.widget.Toast.makeText(this, info, android.widget.Toast.LENGTH_LONG).show();
        if (statusField != null) statusField.setText(info);
    }
}
