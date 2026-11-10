package pl.remotevpn.host;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/** Ephemeral local approval gate. Never persists the pairing code. */
final class PairingGate {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long TTL_MS = 120_000L;
    private static final long STATUS_MS = 60_000L;
    private static String secret = null;
    private static long expires = 0;
    private static int failures = 0;
    private static Pending pending;

    static final class Pending {
        final String token, name, id;
        final byte[] pub;
        final long created;
        boolean approved, denied;
        Pending(String token, String name, byte[] pub, long created) throws Exception {
            this.token=token; this.name=name; this.pub=pub;
            this.id=TrustedControllers.keyId(pub); this.created=created;
        }
    }
    private PairingGate() { }
    static synchronized String begin() {
        end();
        StringBuilder code = new StringBuilder();
        for (int i=0; i<12; i++) code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        secret=code.toString(); expires=SystemClock.elapsedRealtime()+TTL_MS;
        return secret;
    }
    static synchronized void end() { secret=null; pending=null; failures=0; expires=0; }
    static synchronized String visibleCode() {
        if (secret==null || SystemClock.elapsedRealtime()>expires) return null;
        return secret;
    }
    static synchronized String submit(String code, String label, String publicDer) {
        long now=SystemClock.elapsedRealtime();
        if (secret==null || now>expires || pending!=null || failures>=5) return "ERR PAIR_CLOSED";
        byte[] received = code.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] expected = secret.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(received, expected)) {
            failures++;
            if (failures>=5) end();
            return "ERR PAIR_DENIED";
        }
        try {
            byte[] der=Base64.decode(publicDer, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            TrustedControllers.parsePublicKey(der);
            byte[] title=Base64.decode(label, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            String name=new String(title, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (name.isEmpty() || name.length()>40) return "ERR BAD_NAME";
            StringBuilder token=new StringBuilder();
            byte[] bytes=new byte[16]; RANDOM.nextBytes(bytes);
            for (byte b:bytes) token.append(String.format(Locale.ROOT,"%02X",b&255));
            pending=new Pending(token.toString(),name,der,now);
            secret=null; // code consumed; owner must explicitly approve pending request
            return "PENDING "+pending.token;
        } catch (Exception e) { return "ERR BAD_PUBLIC_KEY"; }
    }
    static synchronized Pending awaiting() {
        if (pending==null || pending.approved || pending.denied ||
                SystemClock.elapsedRealtime()>pending.created+TTL_MS) return null;
        return pending;
    }
    static synchronized String status(String token) {
        if (pending==null || !pending.token.equals(token)) return "ERR PAIR_UNKNOWN";
        if (SystemClock.elapsedRealtime()>pending.created+TTL_MS+STATUS_MS) { pending=null; return "ERR PAIR_EXPIRED"; }
        if (pending.denied) return "ERR PAIR_REJECTED";
        if (pending.approved) return "PAIRED "+pending.id;
        if (SystemClock.elapsedRealtime()>pending.created+TTL_MS) { pending.denied=true; return "ERR PAIR_EXPIRED"; }
        return "PENDING";
    }
    static synchronized boolean approve(Context context) {
        Pending p=awaiting();
        if (p==null) return false;
        try {
            if (!TrustedControllers.add(context,p.name,p.pub,true)) return false;
            p.approved=true;
            return true;
        } catch (Exception ignored) { return false; }
    }
    static synchronized void deny() { if (pending!=null) pending.denied=true; }
}
