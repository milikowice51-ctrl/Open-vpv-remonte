package pl.remotevpn.tv;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Compatibility client for RemoteHostService protocol v1.
 *
 * Accepts a self-signed certificate ONLY when the exact SHA-256 certificate
 * fingerprint, supplied separately by the device owner, matches. The secret
 * token is never transmitted before that verification.
 * v0.3 adds explicitly authorized D-pad commands; screen capture is not implemented.
 */
public final class TlsProbeClient {
    public static final int DEFAULT_PORT = 48376;
    private static final int LIMIT = 4096;
    private static final int TIMEOUT_MS = 8000;

    public static final class Result {
        public final long pingMillis;
        public final String jsonInfo;
        public Result(long pingMillis, String jsonInfo) {
            this.pingMillis = pingMillis;
            this.jsonInfo = jsonInfo;
        }
    }

    private TlsProbeClient() { }

    public static Result probe(String host, int port, String token, String fingerprint) throws Exception {
        if (host == null || host.trim().isEmpty() || host.length() > 255 || port < 1 || port > 65535)
            throw new IllegalArgumentException("Nieprawidłowy adres lub port");
        String key = normalizeHex(token, 32);
        byte[] expected = decodeHex(normalizeHex(fingerprint, 64));

        // Trust is established using an exact certificate pin below, not the
        // platform CA store. NEVER send the auth key before pinning succeeds.
        X509TrustManager pinOnly = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] certificates, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] certificates, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{pinOnly}, new SecureRandom());
        SSLSocketFactory factory = context.getSocketFactory();

        try (Socket transport = new Socket()) {
            transport.connect(new InetSocketAddress(host.trim(), port), TIMEOUT_MS);
            transport.setSoTimeout(TIMEOUT_MS);
            try (SSLSocket socket = (SSLSocket) factory.createSocket(transport, host.trim(), port, true)) {
                socket.setSoTimeout(TIMEOUT_MS);
                List<String> protocols = new ArrayList<>();
                for (String name : socket.getSupportedProtocols()) {
                    if ("TLSv1.2".equals(name) || "TLSv1.3".equals(name)) protocols.add(name);
                }
                if (protocols.isEmpty()) throw new SecurityException("TLS 1.2 lub 1.3 niedostępny");
                socket.setEnabledProtocols(protocols.toArray(new String[0]));
                socket.startHandshake();
                byte[] der = socket.getSession().getPeerCertificates()[0].getEncoded();
                byte[] actual = MessageDigest.getInstance("SHA-256").digest(der);
                if (!MessageDigest.isEqual(expected, actual))
                    throw new SecurityException("Odcisk SHA-256 certyfikatu NIE pasuje. Odmowa połączenia.");

                if (!"HELLO 1".equals(readLine(socket)))
                    throw new IllegalStateException("Nieobsługiwany protokół hosta");
                writeLine(socket, "AUTH " + key);
                String authenticated = readLine(socket);
                if (!"OK 1".equals(authenticated))
                    throw new SecurityException("Host odrzucił kod sesji");

                long start = System.nanoTime();
                writeLine(socket, "PING");
                if (!readLine(socket).startsWith("PONG "))
                    throw new IllegalStateException("Brak PONG od urządzenia");
                long elapsed = (System.nanoTime() - start) / 1_000_000L;
                writeLine(socket, "INFO");
                String details = readLine(socket);
                if (!details.startsWith("INFO "))
                    throw new IllegalStateException("Brak odpowiedzi INFO");
                writeLine(socket, "QUIT");
                if (!"BYE".equals(readLine(socket)))
                    throw new IllegalStateException("Nieprawidłowe zakończenie sesji");
                return new Result(elapsed, details.substring(5));
            }
        }
    }

    /** Send an authorized D-pad command with new TLS/certificate verification. */
    public static String sendTvKey(String host, int port, String token,
                                   String fingerprint, String tvKey) throws Exception {
        if (host == null || host.trim().isEmpty() || host.length() > 255 || port < 1 || port > 65535)
            throw new IllegalArgumentException("Nieprawidłowy adres lub port");
        if (!tvKey.matches("UP|DOWN|LEFT|RIGHT|OK|BACK|HOME"))
            throw new IllegalArgumentException("Niedozwolona komenda TV");
        String key = normalizeHex(token, 32);
        byte[] expected = decodeHex(normalizeHex(fingerprint, 64));
        X509TrustManager pinOnly = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] certificates, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] certificates, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{pinOnly}, new SecureRandom());
        try (Socket transport = new Socket()) {
            transport.connect(new InetSocketAddress(host.trim(), port), TIMEOUT_MS);
            transport.setSoTimeout(TIMEOUT_MS);
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(transport, host.trim(), port, true)) {
                socket.setSoTimeout(TIMEOUT_MS);
                List<String> protocols = new ArrayList<>();
                for (String name : socket.getSupportedProtocols())
                    if ("TLSv1.2".equals(name) || "TLSv1.3".equals(name)) protocols.add(name);
                if (protocols.isEmpty()) throw new SecurityException("TLS 1.2+ niedostępny");
                socket.setEnabledProtocols(protocols.toArray(new String[0]));
                socket.startHandshake();
                byte[] der = socket.getSession().getPeerCertificates()[0].getEncoded();
                if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(der)))
                    throw new SecurityException("Odcisk certyfikatu hosta jest inny");
                if (!"HELLO 1".equals(readLine(socket)))
                    throw new IllegalStateException("Nieobsługiwany protokół");
                writeLine(socket, "AUTH " + key);
                if (!"OK 1".equals(readLine(socket))) throw new SecurityException("Zły kod sesji");
                writeLine(socket, "KEY " + tvKey);
                String result = readLine(socket);
                if (!("OK KEY " + tvKey).equals(result)) {
                    if (result.startsWith("ERR ")) throw new SecurityException("TV: " + result.substring(4));
                    throw new IllegalStateException("Nieoczekiwana odpowiedź hosta");
                }
                writeLine(socket, "QUIT");
                if (!"BYE".equals(readLine(socket)))
                    throw new IllegalStateException("Błąd zamknięcia sesji");
                return result;
            }
        }
    }

    private static void writeLine(SSLSocket socket, String value) throws Exception {
        if (value.contains("\r") || value.contains("\n")) throw new SecurityException("Nieprawidłowe polecenie");
        socket.getOutputStream().write((value + "\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static String readLine(SSLSocket socket) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (out.size() <= LIMIT) {
            int ch = socket.getInputStream().read();
            if (ch == -1) throw new java.io.EOFException("Host zakończył połączenie");
            if (ch == '\n') return new String(out.toByteArray(), StandardCharsets.UTF_8).replace("\r", "");
            out.write(ch);
        }
        throw new SecurityException("Zbyt długa odpowiedź");
    }

    private static String normalizeHex(String value, int size) {
        if (value == null) throw new IllegalArgumentException("Brak kodu sesji lub SHA-256");
        String hex = value.trim().replace(":", "").replace("-", "").replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        if (hex.length() != size || !hex.matches("[0-9A-F]+"))
            throw new IllegalArgumentException("Wymagane dokładnie " + size + " znaków HEX");
        return hex;
    }

    private static byte[] decodeHex(String hex) {
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) ((Character.digit(hex.charAt(2*i), 16) << 4)
                    | Character.digit(hex.charAt(2*i + 1), 16));
        }
        return result;
    }
}
