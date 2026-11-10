package pl.remotevpn.host;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.IpPrefix;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import java.net.InetAddress;

/** Android's tunnel owner. OpenVPN3 handles protocol/TLS/packet encryption, not this service. */
public final class EmbeddedVpnService extends VpnService {
    static final String ACTION_CONNECT = "pl.remotevpn.host.CONNECT_EMBEDDED_VPN";
    static final String ACTION_STOP = "pl.remotevpn.host.STOP_EMBEDDED_VPN";
    static volatile String status = "Rozłączony";
    static volatile String vpnIp = "";
    private static final String CHANNEL = "remotevpn_vpn";
    private NativeOpenVpn core;
    private Thread worker;
    private long handle;
    private VpnService.Builder builder;
    private ParcelFileDescriptor tun;
    private boolean assignedAddress;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Połączenie OpenVPN", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            shutdown(); stopSelf(); return START_NOT_STICKY;
        }
        if (!ACTION_CONNECT.equals(intent.getAction())) return START_NOT_STICKY;
        if (VpnService.prepare(this) != null) {
            status = "Brak zgody systemowej na VPN";
            stopSelf(); return START_NOT_STICKY;
        }
        if (worker != null && worker.isAlive()) return START_NOT_STICKY;
        final String profile;
        final String username = intent.getStringExtra("username") == null ? "" : intent.getStringExtra("username");
        final String password = intent.getStringExtra("password") == null ? "" : intent.getStringExtra("password");
        try {
            profile = SecureProfileStore.load(this);
            OvpnProfilePolicy.validate(profile);
        } catch (Exception e) {
            status = "Błąd profilu: " + e.getMessage(); stopSelf(); return START_NOT_STICKY;
        }
        Intent stopIntent = new Intent(this, EmbeddedVpnService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 71, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Android Remote VPN — OpenVPN")
                .setContentText("Łączenie przez wbudowany silnik VPN")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Rozłącz VPN", stopPending).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(71, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        else startForeground(71, notification);
        try {
            core = new NativeOpenVpn(this);
            handle = core.create();
            if (handle == 0) throw new IllegalStateException("Nie można uruchomić OpenVPN Core");
            final long startHandle = handle;
            status = "Łączenie...";
            worker = new Thread(() -> {
                try { core.run(startHandle, profile, username, password); }
                catch (Throwable e) { status = "Błąd silnika: " + e.getClass().getSimpleName(); }
                finally { onTeardown(); status = "Rozłączony"; stopSelf(); }
            }, "openvpn3-worker");
            worker.start();
        } catch (Throwable error) {
            status = "Silnik VPN niedostępny: " + error.getClass().getSimpleName();
            stopSelf();
        }
        return START_NOT_STICKY;
    }
    private synchronized void shutdown() {
        if (core != null && handle != 0) core.stop(handle);
        Thread task = worker;
        if (task != null && task != Thread.currentThread()) {
            try { task.join(2500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        // Native object must not be destroyed while core.run() still holds it.
        if (task == null || !task.isAlive()) {
            if (core != null && handle != 0) core.destroy(handle);
            handle = 0;
        }
        onTeardown();
    }
    @Override public void onDestroy() { shutdown(); super.onDestroy(); }
    @Override public void onRevoke() { shutdown(); stopSelf(); super.onRevoke(); }
    synchronized boolean onBegin() {
        onTeardown();
        assignedAddress = false;
        builder = new Builder().setSession("Android Remote VPN — OpenVPN");
        return true;
    }
    synchronized boolean onAddress(String addr, int prefix) {
        try {
            if (builder == null) return false;
            builder.addAddress(InetAddress.getByName(addr), prefix);
            assignedAddress = true;
            if (addr.indexOf(':') < 0) vpnIp = addr;
            return true;
        } catch (Exception e) { status = "Nieprawidłowy adres TUN"; return false; }
    }
    synchronized boolean onRoute(String addr, int prefix) {
        try {
            if (builder == null) return false;
            builder.addRoute(InetAddress.getByName(addr), prefix); return true;
        } catch (Exception e) { status = "Nieprawidłowa trasa VPN"; return false; }
    }
    synchronized boolean onExcludeRoute(String addr, int prefix) {
        if (Build.VERSION.SDK_INT < 33 || builder == null) return false;
        try { builder.excludeRoute(new IpPrefix(InetAddress.getByName(addr), prefix)); return true; }
        catch (Exception e) { return false; }
    }
    synchronized boolean onDns(String addr) {
        try { if (builder == null) return false; builder.addDnsServer(InetAddress.getByName(addr)); return true; }
        catch (Exception e) { return false; }
    }
    synchronized boolean onSearchDomain(String domain) {
        try { if (builder == null) return false; builder.addSearchDomain(domain); return true; }
        catch (Exception e) { return false; }
    }
    synchronized boolean onMtu(int mtu) {
        try { if (builder == null || mtu < 576 || mtu > 9000) return false; builder.setMtu(mtu); return true; }
        catch (Exception e) { return false; }
    }
    synchronized int onEstablish() {
        if (builder == null || !assignedAddress || VpnService.prepare(this) != null) return -1;
        try {
            tun = builder.establish();
            if (tun == null) return -1;
            int fd = tun.detachFd();  // native OpenVPN core becomes sole owner
            tun = null;
            status = "Tunel zestawiony — " + vpnIp;
            return fd;
        } catch (Exception e) { status = "Nie udało się zestawić tunelu"; return -1; }
    }
    synchronized void onTeardown() {
        builder = null;
        if (tun != null) { try { tun.close(); } catch (Exception ignored) {} tun = null; }
        vpnIp = "";
    }
    void onState(String value) {
        if (value != null && value.length() <= 100) status = value;
    }
}
