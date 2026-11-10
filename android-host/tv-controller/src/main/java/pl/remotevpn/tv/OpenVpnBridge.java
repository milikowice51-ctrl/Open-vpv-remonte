package pl.remotevpn.tv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import de.blinkt.openvpn.api.IOpenVPNAPIService;

final class OpenVpnBridge {
    static final int REQUEST_API = 201;
    static final int REQUEST_VPN = 202;
    private final Activity activity;
    private final Listener listener;
    private IOpenVPNAPIService service;
    private boolean bound;
    private String pendingProfile;
    interface Listener { void status(String message); }
    OpenVpnBridge(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }
    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IOpenVPNAPIService.Stub.asInterface(binder);
            listener.status("Połączono z API OpenVPN for Android.");
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            listener.status("Utracono kontakt z OpenVPN for Android.");
        }
    };
    void bind() {
        if (bound) return;
        Intent serviceIntent = new Intent(IOpenVPNAPIService.class.getName());
        serviceIntent.setPackage("de.blinkt.openvpn");
        try {
            bound = activity.bindService(serviceIntent, conn, Context.BIND_AUTO_CREATE);
            if (!bound) listener.status("Zainstaluj aplikację OpenVPN for Android (Arne Schwabe).");
        } catch (Exception e) {
            listener.status("Nie można połączyć z OpenVPN for Android: " + e.getClass().getSimpleName());
        }
    }
    void unbind() {
        if (bound) activity.unbindService(conn);
        bound = false;
        service = null;
    }
    void start(String config) {
        if (service == null) {
            listener.status("Najpierw zainstaluj OpenVPN for Android lub ponów połączenie z jego API.");
            bind(); return;
        }
        pendingProfile = config;
        prepareApi();
    }
    private void prepareApi() {
        try {
            Intent permission = service.prepare(activity.getPackageName());
            if (permission != null) {
                activity.startActivityForResult(permission, REQUEST_API);
            } else prepareVpn();
        } catch (Exception e) { fail(e); }
    }
    private void prepareVpn() {
        try {
            Intent permission = service.prepareVPNService();
            if (permission != null) {
                activity.startActivityForResult(permission, REQUEST_VPN);
            } else connect();
        } catch (Exception e) { fail(e); }
    }
    private void connect() {
        try {
            if (pendingProfile == null) return;
            // startVPN accepts complete inline configuration; the daemon starts asynchronously.
            service.startVPN(pendingProfile);
            listener.status("Wysłano profil do OpenVPN. Zaczekaj na zestawienie VPN.");
            pendingProfile = null;
        } catch (Exception e) { fail(e); }
    }
    void onPermissionResult(int request, int result) {
        if (result != Activity.RESULT_OK) { pendingProfile = null; listener.status("Nie udzielono zgody na OpenVPN."); return; }
        if (request == REQUEST_API) prepareVpn();
        if (request == REQUEST_VPN) connect();
    }
    void disconnect() {
        try {
            if (service != null) {
                service.disconnect();
                listener.status("Rozłączono OpenVPN.");
            }
        } catch (Exception e) { fail(e); }
    }
    private void fail(Exception e) {
        pendingProfile = null;
        listener.status("Błąd OpenVPN API: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
}
