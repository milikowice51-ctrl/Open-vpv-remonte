package pl.remotevpn.host;

/** JNI binding to the actual OpenVPN 3 Core client. Not a simulated VPN. */
final class NativeOpenVpn {
    static {
        System.loadLibrary("remoteopenvpn");
    }
    private final EmbeddedVpnService vpn;
    NativeOpenVpn(EmbeddedVpnService vpn) { this.vpn = vpn; }
    native long create();
    native void run(long handle, String ovpnConfig, String username, String password);
    native void stop(long handle);
    native void destroy(long handle);

    // Called by JNI from the OpenVPN core's worker thread.
    public boolean onBegin() { return vpn.onBegin(); }
    public boolean onAddress(String ip, int prefix) { return vpn.onAddress(ip, prefix); }
    public boolean onRoute(String ip, int prefix) { return vpn.onRoute(ip, prefix); }
    public boolean onExcludeRoute(String ip, int prefix) { return vpn.onExcludeRoute(ip, prefix); }
    public boolean onDns(String ip) { return vpn.onDns(ip); }
    public boolean onSearchDomain(String domain) { return vpn.onSearchDomain(domain); }
    public boolean onMtu(int mtu) { return vpn.onMtu(mtu); }
    public int onEstablish() { return vpn.onEstablish(); }
    public boolean onProtect(int fd) { return vpn.protect(fd); }
    public void onState(String state) { vpn.onState(state); }
    public void onTeardown() { vpn.onTeardown(); }
}
