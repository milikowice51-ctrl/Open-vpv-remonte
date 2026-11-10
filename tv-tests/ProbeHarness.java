import pl.remotevpn.tv.TlsProbeClient;
public final class ProbeHarness {
    public static void main(String[] args) throws Exception {
        TlsProbeClient.Result result = TlsProbeClient.probe(
                args[0], Integer.parseInt(args[1]), args[2], args[3]);
        System.out.println("PING=" + result.pingMillis + " INFO=" + result.jsonInfo);
    }
}
