package pl.remotevpn.host;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Only the local UI may add/remove controllers. Never accept these operations over TCP. */
final class TrustedControllers {
    private static final String PREFS = "remote_vpn_trust_v1";
    private static final String DEVICES = "trusted";
    private static final int MAX_DEVICES = 20;
    private TrustedControllers() { }

    static final class Device {
        final String id, name, publicDer;
        final boolean tvControl;
        Device(String id, String name, String publicDer, boolean tvControl) {
            this.id = id; this.name = name; this.publicDer = publicDer; this.tvControl = tvControl;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    static boolean autoAccept(Context c) { return prefs(c).getBoolean("auto_accept", true); }
    static boolean pilotEnabled(Context c) { return prefs(c).getBoolean("pilot", true); }
    static boolean extraBanner(Context c) { return prefs(c).getBoolean("extra_banner", false); }
    static boolean paused(Context c) { return prefs(c).getBoolean("paused", false); }
    static void setAutoAccept(Context c, boolean value) { prefs(c).edit().putBoolean("auto_accept", value).commit(); RemoteHostService.refreshNotification(); }
    static void setPilotEnabled(Context c, boolean value) { prefs(c).edit().putBoolean("pilot", value).commit(); RemoteHostService.refreshNotification(); }
    static void setExtraBanner(Context c, boolean value) { prefs(c).edit().putBoolean("extra_banner", value).commit(); }
    static void setPaused(Context c, boolean value) { prefs(c).edit().putBoolean("paused", value).commit(); RemoteHostService.refreshNotification(); }

    static synchronized List<Device> list(Context c) {
        List<Device> result = new ArrayList<>();
        try {
            JSONArray json = new JSONArray(prefs(c).getString(DEVICES, "[]"));
            for (int i = 0; i < json.length(); i++) {
                JSONObject item = json.getJSONObject(i);
                result.add(new Device(item.getString("id"), item.getString("name"),
                        item.getString("pub"), item.optBoolean("tv", false)));
            }
        } catch (Exception ignored) { /* corruption must fail closed: no trusted keys */ }
        return result;
    }
    static synchronized Device get(Context c, String id) {
        for (Device d : list(c)) if (d.id.equals(id)) return d;
        return null;
    }
    static String keyId(byte[] der) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format(Locale.ROOT, "%02X", b & 0xff));
        return result.toString();
    }
    static PublicKey parsePublicKey(byte[] encoded) throws Exception {
        if (encoded.length < 65 || encoded.length > 500) throw new SecurityException("Bad key length");
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
        if (!(key instanceof ECPublicKey) ||
                ((ECPublicKey) key).getParams().getCurve().getField().getFieldSize() != 256)
            throw new SecurityException("Only P-256 public keys are supported");
        return key;
    }
    static synchronized boolean add(Context c, String label, byte[] encoded, boolean pilot) throws Exception {
        parsePublicKey(encoded);
        String id = keyId(encoded);
        List<Device> all = list(c);
        for (Device device : all) if (device.id.equals(id)) return true;
        if (all.size() >= MAX_DEVICES) return false;
        all.add(new Device(id, label.replaceAll("[\\p{Cntrl}]", " ").trim(),
                Base64.encodeToString(encoded, Base64.NO_WRAP), pilot));
        return write(c, all);
    }
    static synchronized boolean revoke(Context c, String id) {
        List<Device> all = list(c);
        boolean removed = all.removeIf(d -> d.id.equals(id));
        return removed && write(c, all);
    }
    private static boolean write(Context c, List<Device> all) {
        try {
            JSONArray json = new JSONArray();
            for (Device d : all) {
                JSONObject item = new JSONObject();
                item.put("id", d.id); item.put("name", d.name);
                item.put("pub", d.publicDer); item.put("tv", d.tvControl);
                json.put(item);
            }
            return prefs(c).edit().putString(DEVICES, json.toString()).commit();
        } catch (Exception e) { return false; }
    }
}
