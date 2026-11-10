package pl.remotevpn.tv;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import org.json.JSONObject;

/** Trusted controller on Android TV: hardware-backed key where available, TLS pin first. */
final class TrustedTvClient {
    private static final String ALIAS = "android-remote-vpn-tv-controller-v1";
    private static final int PORT_TIMEOUT_MS = 8000;
    private TrustedTvClient() { }
    private static KeyStore keystore() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null); return store;
    }
    static boolean pairedIdentityExists() throws Exception { return keystore().containsAlias(ALIAS); }
    private static PublicKey publicKey(boolean create) throws Exception {
        KeyStore store=keystore();
        if (!store.containsAlias(ALIAS)) {
            if (!create) throw new SecurityException("Najpierw sparuj kontroler z hostem");
            KeyPairGenerator generator=KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build());
            generator.generateKeyPair();
        }
        return store.getCertificate(ALIAS).getPublicKey();
    }
    private static String keyId(PublicKey key) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
    }
    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder();
        for (byte b : data) out.append(String.format(Locale.ROOT,"%02X",b&255));
        return out.toString();
    }
    private static String b64(byte[] data) {
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
    private static String normalizeHex(String text,int len) {
        if (text == null) throw new IllegalArgumentException("Brak SHA-256");
        String pin=text.replace(":", "").replace(" ", "").replace("-", "").trim().toUpperCase(Locale.ROOT);
        if (pin.length()!=len || !pin.matches("[A-F0-9]+")) throw new IllegalArgumentException("Wymagane "+len+" cyfr HEX");
        return pin;
    }
    private static SSLSocket verified(String host,int port,String fingerprint) throws Exception {
        String pin=normalizeHex(fingerprint,64);
        if (host.isEmpty() || host.length()>255 || port<1 || port>65535) throw new IllegalArgumentException("Błędny adres/port");
        X509TrustManager tempTrust=new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String auth) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String auth) { }
            @Override public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
        };
        SSLContext context=SSLContext.getInstance("TLS");
        context.init(null,new TrustManager[]{tempTrust},new SecureRandom());
        Socket raw=new Socket();
        try {
            raw.connect(new InetSocketAddress(host,port),PORT_TIMEOUT_MS);
            SSLSocket sock=(SSLSocket)context.getSocketFactory().createSocket(raw,host,port,true);
            try {
                sock.setSoTimeout(PORT_TIMEOUT_MS);
                List<String> enabled=new ArrayList<>();
                for(String p:sock.getSupportedProtocols())
                    if ("TLSv1.2".equals(p)||"TLSv1.3".equals(p)) enabled.add(p);
                sock.setEnabledProtocols(enabled.toArray(new String[0]));
                sock.startHandshake();
                byte[] actual=MessageDigest.getInstance("SHA-256").digest(
                        sock.getSession().getPeerCertificates()[0].getEncoded());
                if (!hex(actual).equals(pin)) throw new SecurityException("Certyfikat TLS hosta jest inny");
                if (!"HELLO 1".equals(read(sock))) throw new SecurityException("Błędna wersja protokołu");
                return sock;
            } catch (Exception e) { sock.close(); throw e; }
        } catch (Exception e) { raw.close(); throw e; }
    }
    private static void send(SSLSocket socket,String value) throws Exception {
        if(value.contains("\n")||value.contains("\r"))throw new SecurityException("Niepoprawne dane");
        socket.getOutputStream().write((value+"\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }
    private static String read(SSLSocket socket)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int i=0;i<4096;i++) {
            int b=socket.getInputStream().read();
            if(b<0)throw new java.io.EOFException("Połączenie zakończone");
            if(b==10)return new String(out.toByteArray(),StandardCharsets.UTF_8).replace("\r","");
            out.write(b);
        }
        throw new SecurityException("Za długa linia");
    }
    static String pair(String host,int port,String fp,String code,String label)throws Exception {
        if (code==null || !code.trim().toUpperCase(Locale.ROOT).matches("[A-Z2-9]{12}"))
            throw new IllegalArgumentException("Wpisz 12-znakowy kod parowania");
        if (label==null || label.trim().isEmpty() || label.length()>40)throw new IllegalArgumentException("Błędna nazwa");
        PublicKey pub=publicKey(true);
        String id=keyId(pub);
        String ticket;
        try (SSLSocket sock=verified(host,port,fp)) {
            send(sock,"PAIR "+code.trim().toUpperCase(Locale.ROOT)+" "+b64(label.trim().getBytes(StandardCharsets.UTF_8))+
                    " "+b64(pub.getEncoded()));
            String response=read(sock);
            if (!response.startsWith("PENDING "))throw new SecurityException(response);
            ticket=normalizeHex(response.substring(8),32);
        }
        long until=android.os.SystemClock.elapsedRealtime()+120_000L;
        while (android.os.SystemClock.elapsedRealtime()<until) {
            Thread.sleep(2000);
            try(SSLSocket sock=verified(host,port,fp)) {
                send(sock,"PAIR_STATUS "+ticket);
                String status=read(sock);
                if (status.equals("PENDING"))continue;
                if (status.equals("PAIRED "+id))return id;
                throw new SecurityException(status);
            }
        }
        throw new java.net.SocketTimeoutException("Nie zatwierdzono na telewizorze");
    }
    private static SSLSocket login(String host,int port,String fp)throws Exception {
        PublicKey pub=publicKey(false);
        String id=keyId(pub);
        SSLSocket sock=verified(host,port,fp);
        try {
            send(sock,"LOGIN "+id);
            String challenge=read(sock);
            if (!challenge.startsWith("CHALLENGE "))throw new SecurityException(challenge);
            String nonce=normalizeHex(challenge.substring(10),64);
            KeyStore store=keystore();
            PrivateKey privateKey=(PrivateKey)store.getKey(ALIAS,null);
            Signature signer=Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(("ARDVPN-LOGIN-V1|"+normalizeHex(fp,64)+"|"+id+"|"+nonce)
                    .getBytes(StandardCharsets.US_ASCII));
            send(sock,"SIGN "+b64(signer.sign()));
            String result=read(sock);
            if (!result.equals("OK 1"))throw new SecurityException(result);
            return sock;
        } catch (Exception e){ sock.close(); throw e; }
    }
    static String sendKey(String host,int port,String fp,String tvKey)throws Exception {
        if (!tvKey.matches("UP|DOWN|LEFT|RIGHT|OK|BACK|HOME"))throw new IllegalArgumentException("Nieznany przycisk");
        try(SSLSocket sock=login(host,port,fp)){
            send(sock,"KEY "+tvKey);
            String result=read(sock);
            if (!result.equals("OK KEY "+tvKey))throw new SecurityException(result);
            send(sock,"QUIT"); read(sock);
            return result;
        }
    }
    static TlsProbeClient.Result probe(String host,int port,String fp)throws Exception {
        try(SSLSocket sock=login(host,port,fp)){
            long start=System.nanoTime();
            send(sock,"PING");
            if (!read(sock).startsWith("PONG "))throw new SecurityException("Niepoprawne PONG");
            long latency=(System.nanoTime()-start)/1_000_000L;
            send(sock,"INFO");
            String info=read(sock);
            if (!info.startsWith("INFO "))throw new SecurityException(info);
            send(sock,"QUIT"); read(sock);
            return new TlsProbeClient.Result(latency,info.substring(5));
        }
    }
}
