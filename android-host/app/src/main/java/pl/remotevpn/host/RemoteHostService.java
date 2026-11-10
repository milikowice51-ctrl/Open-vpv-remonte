package pl.remotevpn.host;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import android.util.Base64;
import java.security.SecureRandom;
import java.util.Locale;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

public final class RemoteHostService extends Service {
    static final int PORT = 48376;
    static final String ACTION_START = "pl.remotevpn.host.START";
    static final String ACTION_STOP = "pl.remotevpn.host.STOP";
    private static final int NOTIF_ID = 4001;
    private static final String CHANNEL_ID = "remote_host_visible";
    static volatile String status = "Serwer wyłączony";
    static volatile String address = "";
    static volatile String sessionKey = "";
    static volatile String fingerprint = "";
    static volatile boolean running = false;
    static volatile boolean controlArmed = false;
    static volatile long lastRemoteCommandAt = 0L;
    private static volatile RemoteHostService activeService;
    private volatile boolean shouldRun = false;
    private SSLServerSocket serverSocket;
    private Thread worker;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopServer(); stopSelf(); return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (shouldRun) return START_NOT_STICKY;
        Inet4Address vpn = VpnAddress.find(this);
        if (vpn == null) {
            status = "Brak aktywnego VPN — nie uruchomiono serwera";
            stopSelf(); return START_NOT_STICKY;
        }
        createChannel();
        activeService = this;
        Notification notification = notification(statusNotificationText());
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else startForeground(NOTIF_ID, notification);
        controlArmed = false; // Legacy session-code access always needs local opt-in.
        lastRemoteCommandAt = 0L;
        shouldRun = true;
        worker = new Thread(() -> runListener(vpn), "remote-vpn-tls-listener");
        worker.start();
        return START_NOT_STICKY;
    }

    private void runListener(Inet4Address vpn) {
        try {
            byte[] random = new byte[16];
            new SecureRandom().nextBytes(random);
            StringBuilder sb = new StringBuilder();
            for (byte b : random) sb.append(String.format(Locale.ROOT, "%02X", b & 0xff));
            sessionKey = sb.toString();
            fingerprint = TlsIdentity.fingerprint();
            address = vpn.getHostAddress();
            SSLServerSocket s = (SSLServerSocket) TlsIdentity.socketFactory().createServerSocket();
            serverSocket = s;
            // Starsze Androidy nie zawsze obsługują TLSv1.3; TLSv1.2 jest minimum.
            java.util.ArrayList<String> supported = new java.util.ArrayList<>();
            for (String protocol : s.getSupportedProtocols()) {
                if (protocol.equals("TLSv1.2") || protocol.equals("TLSv1.3")) supported.add(protocol);
            }
            s.setEnabledProtocols(supported.toArray(new String[0]));
            s.bind(new java.net.InetSocketAddress(vpn, PORT), 2);
            s.setSoTimeout(1500);
            running = true;
            status = "Nasłuchiwanie wyłącznie na " + address + ":" + PORT;
            while (shouldRun) {
                // Przerywamy dostęp, gdy VPN zostanie odłączony lub zmieni IP.
                Inet4Address current = VpnAddress.find(this);
                if (current == null || !current.equals(vpn)) break;
                try {
                    SSLSocket socket = (SSLSocket) s.accept();
                    handleClient(socket);
                } catch (SocketTimeoutException ignored) { }
                catch (java.io.IOException e) {
                    if (shouldRun) status = "Błąd klienta TLS: " + e.getClass().getSimpleName();
                }
            }
        } catch (Exception e) {
            status = "Błąd serwera: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.e("RemoteHostService", "Listener error (no secrets)", e);
        } finally {
            shouldRun = false;
            controlArmed = false;
            PairingGate.end();
            activeService = null;
            closeSocket();
            running = false;
            sessionKey = "";
            address = "";
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void handleClient(SSLSocket socket) {
        try (SSLSocket s = socket) {
            s.setSoTimeout(8000);
            s.startHandshake();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            write(out, "HELLO 1"); // Backwards compatible with v0.3 controllers.
            String request = readBounded(in);
            if (request == null) return;
            if (request.startsWith("PAIR ")) {
                String[] parts = request.split(" ", -1);
                write(out, parts.length == 4 ? PairingGate.submit(parts[1], parts[2], parts[3])
                        : "ERR BAD_PAIR_REQUEST");
                return;
            }
            if (request.startsWith("PAIR_STATUS ")) {
                write(out, PairingGate.status(request.substring(12).trim()));
                return;
            }
            String trustedId = null;
            if (request.startsWith("LOGIN ")) {
                String id = request.substring(6).trim();
                TrustedControllers.Device trusted = TrustedControllers.get(this, id);
                if (trusted == null || TrustedControllers.paused(this)) {
                    write(out, "ERR NOT_TRUSTED"); return;
                }
                byte[] nonce = new byte[32]; new SecureRandom().nextBytes(nonce);
                StringBuilder hexNonce = new StringBuilder();
                for (byte b : nonce) hexNonce.append(String.format(Locale.ROOT, "%02X", b & 255));
                write(out, "CHALLENGE " + hexNonce);
                String signed = readBounded(in);
                if (signed == null || !signed.startsWith("SIGN ")) { write(out, "ERR SIGNATURE_REQUIRED"); return; }
                try {
                    Signature verifier = Signature.getInstance("SHA256withECDSA");
                    byte[] publicDer = Base64.decode(trusted.publicDer, Base64.DEFAULT);
                    verifier.initVerify(TrustedControllers.parsePublicKey(publicDer));
                    String challenge = "ARDVPN-LOGIN-V1|" + fingerprint + "|" + id + "|" + hexNonce;
                    verifier.update(challenge.getBytes(StandardCharsets.US_ASCII));
                    byte[] sig = Base64.decode(signed.substring(5).trim(),
                            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                    if (!verifier.verify(sig) || TrustedControllers.get(this,id)==null
                            || TrustedControllers.paused(this)) {
                        write(out, "ERR BAD_SIGNATURE"); return;
                    }
                } catch (Exception failure) { write(out, "ERR BAD_SIGNATURE"); return; }
                trustedId = id;
            } else if (request.startsWith("AUTH ")) {
                String submitted = request.substring(5).trim().toUpperCase(Locale.ROOT);
                if (sessionKey.isEmpty() || !MessageDigest.isEqual(
                        sessionKey.getBytes(StandardCharsets.US_ASCII),
                        submitted.getBytes(StandardCharsets.US_ASCII))) {
                    write(out, "ERR BAD_KEY"); return;
                }
            } else { write(out, "ERR AUTH_REQUIRED"); return; }
            if (!running) { write(out, "ERR NOT_RUNNING"); return; }
            write(out, "OK 1");
            s.setSoTimeout(10000);
            // Each command is checked against CURRENT stored trust: immediate revocation.
            for (int i = 0; i < 10 && shouldRun; i++) {
                String command = readBounded(in);
                if (command == null) break;
                if (trustedId != null && (TrustedControllers.get(this, trustedId) == null
                        || TrustedControllers.paused(this))) {
                    write(out, "ERR REVOKED"); break;
                }
                if (command.equals("PING")) {
                    write(out, "PONG " + System.currentTimeMillis());
                } else if (command.equals("INFO")) {
                    JSONObject info = new JSONObject();
                    info.put("protocol", 1);
                    info.put("version", "0.5.1");
                    info.put("device", Build.MANUFACTURER + " " + Build.MODEL);
                    info.put("android", Build.VERSION.RELEASE);
                    info.put("vpnAddress", address);
                    info.put("remoteControl", mayControl(trustedId));
                    info.put("tvDpadSupported", Build.VERSION.SDK_INT >= 33);
                    info.put("screenCapture", false);
                    info.put("trustedConnection", trustedId != null);
                    info.put("sessionBannerEnabled", TrustedControllers.extraBanner(this));
                    write(out, "INFO " + info.toString());
                } else if (command.startsWith("KEY ")) {
                    String key = command.substring(4);
                    if (!key.matches("UP|DOWN|LEFT|RIGHT|OK|BACK|HOME"))
                        write(out, "ERR UNKNOWN_KEY");
                    else if (!mayControl(trustedId)) write(out, "ERR CONTROL_NOT_ALLOWED");
                    else {
                        String result = TvRemoteAccessibilityService.performTvKey(key);
                        if (result.startsWith("OK KEY ")) lastRemoteCommandAt=System.currentTimeMillis();
                        write(out, result);
                    }
                } else if (command.equals("QUIT")) {
                    write(out, "BYE"); break;
                } else {
                    write(out, "ERR UNSUPPORTED_COMMAND");
                }
            }
        } catch (Exception ignored) {
            // Secrets and private keys are never included in logs.
        }
    }

    private boolean mayControl(String trustedId) {
        if (!running || !TvRemoteAccessibilityService.isEnabled() ||
                TrustedControllers.paused(this) || !TrustedControllers.pilotEnabled(this)) return false;
        if (trustedId == null) return controlArmed; // Older, explicitly armed session.
        TrustedControllers.Device device = TrustedControllers.get(this, trustedId);
        return device != null && device.tvControl &&
                (TrustedControllers.autoAccept(this) || controlArmed);
    }

    private String readBounded(BufferedReader r) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4096; i++) {
            int ch = r.read();
            if (ch == -1) return null;
            if (ch == '\n') return sb.toString().replace("\r", "");
            if (ch < 32 || ch > 126) throw new java.io.IOException("Invalid protocol character");
            sb.append((char) ch);
        }
        throw new java.io.IOException("Line too long");
    }

    private void write(BufferedWriter writer, String line) throws Exception {
        writer.write(line); writer.write("\n"); writer.flush();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Udostępnianie Android Remote VPN",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Widoczne powiadomienie podczas działania serwera zdalnego dostępu");
        nm.createNotificationChannel(channel);
    }

    private Notification notification(String content) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, RemoteHostService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Android Remote VPN — aktywny")
                .setContentText(content)
                .setContentIntent(openPending)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "ZATRZYMAJ", stopPending).build())
                .build();
    }

    private String statusNotificationText() {
        if (TrustedControllers.paused(this)) return "Dostęp wstrzymany — dotknij, aby zarządzać";
        if (TrustedControllers.autoAccept(this) && TrustedControllers.pilotEnabled(this))
            return "Serwer działa; zaufane kontrolery mogą sterować TV";
        return controlArmed ? "Sterowanie zezwolone lokalnie" : "Host działa; sterowanie ograniczone";
    }
    static void refreshNotification() {
        RemoteHostService service = activeService;
        if (service != null && running) {
            NotificationManager nm = service.getSystemService(NotificationManager.class);
            nm.notify(NOTIF_ID, service.notification(service.statusNotificationText()));
        }
    }
    static void setControlArmed(boolean enable) {
        // Only local UI can change the one-session legacy authorization.
        controlArmed = enable && running && TvRemoteAccessibilityService.isEnabled()
                && activeService != null && !TrustedControllers.paused(activeService);
        refreshNotification();
    }

    private void closeSocket() {
        SSLServerSocket s = serverSocket;
        serverSocket = null;
        if (s != null) try { s.close(); } catch (Exception ignored) { }
    }

    private void stopServer() {
        shouldRun = false;
        running = false;
        controlArmed = false;
        PairingGate.end();
        activeService = null;
        closeSocket();
        status = "Serwer zatrzymany";
        sessionKey = "";
        address = "";
    }
    @Override public void onDestroy() {
        stopServer();
        super.onDestroy();
    }
}
