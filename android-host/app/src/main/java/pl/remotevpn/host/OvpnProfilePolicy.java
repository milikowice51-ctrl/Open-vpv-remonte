package pl.remotevpn.host;

import java.util.Locale;

/** Strict first-release policy: credentials and certificates must be in the .ovpn itself. */
public final class OvpnProfilePolicy {
    private OvpnProfilePolicy() {}
    public static void validate(String content) {
        if (content == null || content.length() < 20 || content.length() > 1_048_576) {
            throw new IllegalArgumentException("Profil OpenVPN ma nieprawidłowy rozmiar (max 1 MiB)");
        }
        if (content.indexOf('\0') >= 0) throw new IllegalArgumentException("Plik zawiera NUL");
        if (!content.matches("(?s).*?((?m)^\\s*remote\\s+[^\\s#]+).*")) {
            throw new IllegalArgumentException("Brakuje dyrektywy remote");
        }
        if (!content.matches("(?s).*?((?m)^\\s*client\\s*$|(?m)^\\s*tls-client\\s*$).*")) {
            throw new IllegalArgumentException("Brakuje dyrektywy client lub tls-client");
        }
        // No shell/script directives or external file references. External certificates would
        // otherwise refer to paths outside Android's private app sandbox.
        String[] lines = content.replace("\r", "").split("\n");
        boolean insideInline = false;
        for (String raw : lines) {
            String s = raw.trim();
            if (s.startsWith("<") && s.endsWith(">")) {
                if (s.startsWith("</")) insideInline = false;
                else insideInline = true;
                continue;
            }
            if (insideInline || s.isEmpty() || s.startsWith("#") || s.startsWith(";")) continue;
            String directive = s.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            if (directive.equals("up") || directive.equals("down") || directive.equals("plugin")
                    || directive.equals("script-security") || directive.equals("route-up")
                    || directive.equals("ipchange") || directive.equals("client-connect")
                    || directive.equals("client-disconnect") || directive.equals("learn-address")) {
                throw new IllegalArgumentException("Niedozwolona dyrektywa: " + directive);
            }
            if (directive.equals("ca") || directive.equals("cert") || directive.equals("key")
                    || directive.equals("pkcs12") || directive.equals("tls-auth")
                    || directive.equals("tls-crypt") || directive.equals("tls-crypt-v2")
                    || directive.equals("auth-user-pass") || directive.equals("crl-verify")) {
                if (s.contains(" ") || s.contains("\t")) {
                    // 'auth-user-pass' alone is supported; auth-user-pass file is not.
                    throw new IllegalArgumentException("Wymagane dane inline zamiast pliku: " + directive);
                }
            }
        }
    }
}
