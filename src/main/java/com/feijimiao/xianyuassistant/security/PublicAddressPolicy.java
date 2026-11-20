package com.feijimiao.xianyuassistant.security;

import okhttp3.Dns;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/** Public Internet addresses only; shared by remote images and outbound webhooks. */
public final class PublicAddressPolicy {
    private PublicAddressPolicy() {}

    public static boolean isPublic(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int a = Byte.toUnsignedInt(bytes[0]);
            int b = Byte.toUnsignedInt(bytes[1]);
            int c = Byte.toUnsignedInt(bytes[2]);
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                    || a == 100 && b >= 64 && b <= 127
                    || a == 169 && b == 254
                    || a == 172 && b >= 16 && b <= 31
                    || a == 192 && (b == 168 || b == 0 && (c == 0 || c == 2) || b == 88 && c == 99)
                    || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100)
                    || a == 203 && b == 0 && c == 113);
        }
        if (address instanceof Inet6Address) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                mapped &= bytes[i] == 0;
            }
            if (mapped && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff) {
                try {
                    return isPublic(InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16)));
                } catch (UnknownHostException impossible) {
                    return false;
                }
            }
            // Only native global unicast. Exclude translation/transition, protocol and documentation ranges.
            return (bytes[0] & 0xe0) == 0x20
                    && !(bytes[0] == 0x20 && bytes[1] == 0x01 && (bytes[2] & 0xfe) == 0)
                    && !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == (byte) 0xb8)
                    && !(bytes[0] == 0x20 && bytes[1] == 0x02)
                    && !(bytes[0] == 0x3f && bytes[1] == (byte) 0xff && (bytes[2] & 0xf0) == 0);
        }
        return false;
    }

    /** Never fall back to DNS for another hostname or after a connection failure. */
    public static Dns pinnedDns(String expectedHost, List<InetAddress> addresses) {
        List<InetAddress> pinned = List.copyOf(addresses);
        if (pinned.isEmpty() || pinned.stream().anyMatch(address -> !isPublic(address))) {
            throw new IllegalArgumentException("目标必须仅包含公网地址");
        }
        return hostname -> {
            if (!expectedHost.equalsIgnoreCase(hostname)) {
                throw new UnknownHostException("拒绝解析非目标域名");
            }
            return pinned;
        };
    }
}
