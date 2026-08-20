package org.hongxi.jaws.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.*;
import java.util.Enumeration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 *
 * Network utility class
 * <p>
 * Created by shenhongxi on 2020/8/22.
 */
public class NetUtils {
    private static final Logger log = LoggerFactory.getLogger(NetUtils.class);

    public static final String LOCALHOST = "127.0.0.1";
    public static final String ANY_HOST = "0.0.0.0";
    private static final Pattern LOCAL_IP_PATTERN = Pattern.compile("127(\\.\\d{1,3}){3}$");
    private static final Pattern IP_PATTERN = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3,5}$");
    private static volatile InetAddress LOCAL_ADDRESS = null;

    public static boolean isInvalidLocalHost(String host) {
        return host == null || host.isEmpty()
                || host.equalsIgnoreCase("localhost")
                || host.equals(ANY_HOST)
                || (LOCAL_IP_PATTERN.matcher(host).matches());
    }

    public static boolean isValidLocalHost(String host) {
        return !isInvalidLocalHost(host);
    }

    /**
     * <pre>
     * Lookup strategy: cached ip -> hostname-resolved ip -> socket-connected local ip -> network interface scan
     * </pre>
     *
     * @return loca ip
     */
    public static InetAddress getLocalAddress(Map<String, Integer> destHostPorts) {
        if (LOCAL_ADDRESS != null) {
            return LOCAL_ADDRESS;
        }

        InetAddress localAddress = getLocalAddressByHostname();
        if (!isValidAddress(localAddress)) {
            localAddress = getLocalAddressBySocket(destHostPorts);
        }

        if (!isValidAddress(localAddress)) {
            localAddress = getLocalAddressByNetworkInterface();
        }

        if (isValidAddress(localAddress)) {
            LOCAL_ADDRESS = localAddress;
        }

        return localAddress;
    }

    private static InetAddress getLocalAddressByHostname() {
        try {
            InetAddress localAddress = InetAddress.getLocalHost();
            if (isValidAddress(localAddress)) {
                return localAddress;
            }
        } catch (Throwable e) {
            log.warn("Failed to retrieve local address by hostname", e);
        }
        return null;
    }

    private static InetAddress getLocalAddressBySocket(Map<String, Integer> destHostPorts) {
        if (CollectionUtils.isEmpty(destHostPorts)) {
            return null;
        }

        for (Map.Entry<String, Integer> entry : destHostPorts.entrySet()) {
            String host = entry.getKey();
            int port = entry.getValue();
            try {
                try (Socket socket = new Socket()) {
                    SocketAddress addr = new InetSocketAddress(host, port);
                    socket.connect(addr, 1000);
                    return socket.getLocalAddress();
                } catch (Throwable e) {
                    // ignore
                }
            } catch (Exception e) {
                log.warn("Failed to retrieve local address by connecting to dest host:port({}:{}) failed",
                        host, port, e);
            }
        }
        return null;
    }

    private static InetAddress getLocalAddressByNetworkInterface() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                try {
                    NetworkInterface network = interfaces.nextElement();
                    Enumeration<InetAddress> addresses = network.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        try {
                            InetAddress address = addresses.nextElement();
                            if (isValidAddress(address)) {
                                return address;
                            }
                        } catch (Throwable e) {
                            log.warn("Failed to retrieve ip address", e);
                        }
                    }
                } catch (Throwable e) {
                    log.warn("Failed to retrieve ip address", e);
                }
            }
        } catch (Throwable e) {
            log.warn("Failed to retrieve ip address", e);
        }
        return null;
    }

    public static boolean isValidAddress(InetAddress address) {
        if (address == null || address.isLoopbackAddress()) return false;
        String name = address.getHostAddress();
        return (name != null && !ANY_HOST.equals(name) && !LOCALHOST.equals(name) && IP_PATTERN.matcher(name).matches());
    }

    public static String getHostName(SocketAddress socketAddress) {
        if (socketAddress == null) {
            return null;
        }

        if (socketAddress instanceof InetSocketAddress inetAddr) {
            InetAddress addr = inetAddr.getAddress();
            if (addr != null) {
                return addr.getHostAddress();
            }
        }

        return null;
    }

    public static boolean isPortAvailable(int port) {
        if (port < 1 || port > 65535) {
            return false;
        }
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setReuseAddress(true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
