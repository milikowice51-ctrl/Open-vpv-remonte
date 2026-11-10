import pl.remotevpn.host.OvpnProfilePolicy;
public class TestOvpnProfilePolicy {
    private static int count = 0;
    private static void pass(String title, String body) {
        OvpnProfilePolicy.validate(body); count++; System.out.println("PASS " + title);
    }
    private static void reject(String title, String body) {
        try { OvpnProfilePolicy.validate(body); throw new AssertionError("Allowed unsafe profile: " + title); }
        catch (IllegalArgumentException expected) { count++; System.out.println("PASS " + title); }
    }
    public static void main(String[] args) {
        String base = "client\nremote vpn.example.invalid 1194\nproto udp\n<ca>\nCERTIFICATE\n</ca>\n";
        pass("inline CA", base);
        pass("tls-client", "tls-client\nremote vpn.example.invalid\n<cert>\ndata\n</cert>\n");
        pass("username password prompt", base + "auth-user-pass\n");
        reject("external certificate file", base + "ca /sdcard/ca.pem\n");
        reject("external password file", base + "auth-user-pass /sdcard/secret\n");
        reject("dangerous script", base + "up /tmp/runme\n");
        reject("no client mode", "remote vpn.example.invalid 1194\nproto udp\n");
        reject("no remote", "client\nproto udp\n");
        reject("embedded NUL", base + "\0");
        reject("over 1 MB", base + "#".repeat(1_048_576));
        System.out.println("Passed " + count + " policy tests");
    }
}
