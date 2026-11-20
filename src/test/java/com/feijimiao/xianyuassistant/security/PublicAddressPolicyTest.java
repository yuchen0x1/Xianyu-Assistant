package com.feijimiao.xianyuassistant.security;

import okhttp3.Dns;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicAddressPolicyTest {
    // Numeric fixtures only: address classification does not make network requests or DNS queries.
    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "8.8.8.8", "2606:4700:4700::1111", "2001:4860:4860::8888"})
    void acceptsNativePublicAddresses(String literal) throws Exception {
        assertThat(PublicAddressPolicy.isPublic(InetAddress.getByName(literal))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.1.2.3", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.1.1",
            "172.16.0.1", "192.168.1.1", "192.0.0.1", "192.0.2.1", "192.88.99.1", "198.18.0.1",
            "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "::", "::1", "fe80::1", "fc00::1", "ff02::1", "::ffff:192.168.1.1", "::192.168.1.1",
            "64:ff9b::a00:1", "2001::1", "2001:db8::1", "2002::1", "3fff::1"})
    void rejectsPrivateSpecialAndTransitionAddresses(String literal) throws Exception {
        assertThat(PublicAddressPolicy.isPublic(InetAddress.getByName(literal))).isFalse();
    }

    @Test
    void pinningCopiesAnswersAndRefusesOtherHostnamesWithoutFallback() throws Exception {
        InetAddress approved = InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34});
        List<InetAddress> answers = new ArrayList<>(List.of(approved));
        Dns dns = PublicAddressPolicy.pinnedDns("cdn.example.com", answers);
        answers.clear();

        assertThat(dns.lookup("cdn.example.com")).containsExactly(approved);
        assertThat(dns.lookup("CDN.EXAMPLE.COM")).containsExactly(approved);
        assertThat(dns.lookup("cdn.example.com")).containsExactly(approved);
        assertThatThrownBy(() -> dns.lookup("other.example.com")).isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(() -> dns.lookup("cdn.example.com").clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void pinningFailsClosedForEmptyOrMixedAnswers() throws Exception {
        InetAddress approved = InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34});
        InetAddress local = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        assertThat(PublicAddressPolicy.isPublic(null)).isFalse();
        assertThatThrownBy(() -> PublicAddressPolicy.pinnedDns("example.com", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PublicAddressPolicy.pinnedDns("example.com", List.of(approved, local)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
