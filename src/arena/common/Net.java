package arena.common;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.ExportException;
import java.rmi.server.RMISocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** RMI networking setup shared by server, client and bots. */
public final class Net {
    private Net() {}

    /**
     * Must run before any RMI class is used: the RMI runtime reads these
     * properties once, in static initialisers.
     *
     * @param advertisedHost the address written into every stub this JVM exports,
     *                       i.e. the address remote peers will connect back to
     */
    public static void configureRmi(String advertisedHost) {
        if (advertisedHost != null) {
            System.setProperty("java.rmi.server.hostname", advertisedHost);
        }
        // Fail fast instead of hanging ~20s when a peer vanishes mid-call
        System.setProperty("sun.rmi.transport.tcp.responseTimeout", "4000");
        System.setProperty("sun.rmi.transport.tcp.handshakeTimeout", "4000");
        try {
            RMISocketFactory.setSocketFactory(new FastSocketFactory());
        } catch (IOException alreadySet) {
            // only one factory per JVM; fine if a launcher already installed it
        }
    }

    /** Default RMI sockets, plus a connect timeout and Nagle off (small, frequent messages). */
    private static final class FastSocketFactory extends RMISocketFactory {
        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket s = new Socket();
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), 3000);
            return s;
        }

        @Override
        public ServerSocket createServerSocket(int port) throws IOException {
            return new ServerSocket(port);
        }
    }

    /**
     * Which of our addresses does the OS use to reach the given host?
     * That is exactly the address the server must call us back on.
     */
    public static String localAddressTowards(String host, int port) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 3000);
            return s.getLocalAddress().getHostAddress();
        }
    }

    /**
     * Export a client-side callback object on the fixed callback port, or on
     * any free port if another client on this machine already holds it.
     */
    public static Remote exportCallback(Remote callback) throws RemoteException {
        try {
            return UnicastRemoteObject.exportObject(callback, GameRules.CLIENT_CALLBACK_PORT);
        } catch (ExportException portTaken) {
            return UnicastRemoteObject.exportObject(callback, 0);
        }
    }

    /** "ip:port" a stub points at, read from its toString() (the stub carries its endpoint). */
    public static String endpointOf(Remote stub) {
        String s = String.valueOf(stub);
        int i = s.indexOf("endpoint:[");
        if (i < 0) return "?";
        int j = s.indexOf(']', i);
        return j < 0 ? "?" : s.substring(i + "endpoint:[".length(), j);
    }

    public static String rootCause(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    public record Candidate(String ip, String adapter, int rank) {
        public String describe() {
            String kind = switch (rank) {
                case 0 -> "LAN";
                case 1 -> "VPN / Tailscale";
                case 2 -> "virtual adapter - probably not this one";
                default -> "other";
            };
            return String.format("%-16s %s  (%s)", ip, adapter, kind);
        }
    }

    /** All IPv4 addresses of this machine, most likely LAN address first. */
    public static List<Candidate> candidateAddresses() {
        List<Candidate> result = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getDisplayName() == null ? nif.getName() : nif.getDisplayName();
                String lower = name.toLowerCase(Locale.ROOT);
                boolean virtual = nif.isVirtual() || lower.contains("virtual") || lower.contains("vmware")
                        || lower.contains("vethernet") || lower.contains("hyper-v") || lower.contains("wsl")
                        || lower.contains("docker") || lower.contains("loopback");
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLinkLocalAddress()) continue;
                    byte[] b = a.getAddress();
                    boolean cgnat = (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
                    int rank = virtual ? 2 : a.isSiteLocalAddress() ? 0 : cgnat ? 1 : 3;
                    result.add(new Candidate(a.getHostAddress(), name, rank));
                }
            }
        } catch (IOException e) {
            // fall through with whatever we found
        }
        result.sort(Comparator.comparingInt(Candidate::rank));
        return result;
    }
}
