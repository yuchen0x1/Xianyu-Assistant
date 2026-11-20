package com.feijimiao.xianyuassistant.service.impl;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageUploadServiceImplRemoteFetchTest {

    @Mock private ImageUploadServiceImpl.PinnedClientFactory pinnedClientFactory;
    @Mock private Call.Factory callFactory;
    @Mock private Call call;
    @Mock private Response response;
    @Mock private ResponseBody responseBody;

    private ImageUploadServiceImpl service;
    private InetAddress publicAddress;

    @BeforeEach
    void setUp() throws Exception {
        service = new ImageUploadServiceImpl();
        ReflectionTestUtils.setField(service, "pinnedClientFactory", pinnedClientFactory);
        ReflectionTestUtils.setField(service, "allowedImageHosts", "example.com");
        publicAddress = InetAddress.getByName("93.184.216.34");
        ReflectionTestUtils.setField(service, "hostResolver",
                (ImageUploadServiceImpl.HostResolver) host -> new InetAddress[]{publicAddress});
    }

    @Test
    void rejectsUnlistedInsecureAndCustomPortTargetsBeforeNetworkAccess() {
        assertThrows(IllegalArgumentException.class,
                () -> service.downloadImage("https://attacker.invalid/image.jpg"));
        assertThrows(IllegalArgumentException.class,
                () -> service.downloadImage("http://example.com/image.jpg"));
        assertThrows(IllegalArgumentException.class,
                () -> service.downloadImage("https://example.com:8443/image.jpg"));
        verifyNoInteractions(pinnedClientFactory);
    }

    @Test
    void rejectsAllowedHostnameWhenDnsResolvesToPrivateAddress() throws Exception {
        ReflectionTestUtils.setField(service, "hostResolver",
                (ImageUploadServiceImpl.HostResolver) host ->
                        new InetAddress[]{InetAddress.getByName("127.0.0.1")});

        assertThrows(IllegalArgumentException.class,
                () -> service.validateRemoteImageUri("https://cdn.example.com/image.jpg"));
        verifyNoInteractions(pinnedClientFactory);
    }

    @Test
    void downloadsSmallImageThroughClientPinnedToValidatedAddresses() throws Exception {
        byte[] image = new byte[]{1, 2, 3, 4};
        stubResponse();
        when(response.code()).thenReturn(200);
        when(response.body()).thenReturn(responseBody);
        when(response.header("Content-Type", "")).thenReturn("image/png");
        when(responseBody.contentLength()).thenReturn((long) image.length);
        when(responseBody.byteStream()).thenReturn(new ByteArrayInputStream(image));

        assertArrayEquals(image, service.downloadImage("https://cdn.example.com/image.png"));
    }

    @Test
    void rejectsOversizedOrRedirectedResponses() throws Exception {
        stubResponse();
        when(response.code()).thenReturn(200, 302);
        when(response.body()).thenReturn(responseBody);
        when(response.header("Content-Type", "")).thenReturn("image/jpeg");
        when(responseBody.contentLength()).thenReturn(10L * 1024 * 1024 + 1);

        assertThrows(IOException.class,
                () -> service.downloadImage("https://cdn.example.com/large.jpg"));
        assertThrows(IOException.class,
                () -> service.downloadImage("https://cdn.example.com/redirect.jpg"));
    }

    @Test
    void rejectsHugePixelDimensionsBeforeDecodingImageData() throws Exception {
        assertFalse(service.isSourceImageSizeAllowed(100_000, 100_000));
        assertFalse(service.isSourceImageSizeAllowed(8_193, 1));
        assertTrue(service.isSourceImageSizeAllowed(4_000, 5_000));
        assertNull(service.compressImage(pngWithDimensions(100_000, 100_000)));
        verifyNoInteractions(pinnedClientFactory);
    }

    @Test
    void canonicalizesAllowedHostAndUsesOnlyTheValidatedDnsSnapshot() throws Exception {
        ImageUploadServiceImpl.HostResolver resolver = mock(ImageUploadServiceImpl.HostResolver.class);
        when(resolver.resolve("cdn.example.com")).thenReturn(new InetAddress[]{publicAddress},
                new InetAddress[]{InetAddress.getByAddress(new byte[]{10, 0, 0, 1})});
        ReflectionTestUtils.setField(service, "hostResolver", resolver);
        stubResponse();
        when(response.code()).thenReturn(200);
        when(response.body()).thenReturn(responseBody);
        when(response.header("Content-Type", "")).thenReturn("image/png");
        when(responseBody.contentLength()).thenReturn(1L);
        when(responseBody.byteStream()).thenReturn(new ByteArrayInputStream(new byte[]{1}));

        assertArrayEquals(new byte[]{1}, service.downloadImage("HTTPS://CDN.Example.Com:443/image.png"));
        verify(resolver).resolve("cdn.example.com");
        verify(pinnedClientFactory).create("cdn.example.com", List.of(publicAddress));
    }

    @Test
    void rejectsMixedAndEmptyDnsAnswersBeforeBuildingTransport() throws Exception {
        InetAddress local = InetAddress.getByAddress(new byte[]{10, 0, 0, 1});
        ReflectionTestUtils.setField(service, "hostResolver",
                (ImageUploadServiceImpl.HostResolver) host -> new InetAddress[]{publicAddress, local});
        assertThrows(IllegalArgumentException.class,
                () -> service.downloadImage("https://cdn.example.com/image.png"));
        ReflectionTestUtils.setField(service, "hostResolver",
                (ImageUploadServiceImpl.HostResolver) host -> new InetAddress[0]);
        assertThrows(IllegalArgumentException.class,
                () -> service.downloadImage("https://cdn.example.com/image.png"));
        verifyNoInteractions(pinnedClientFactory);
    }

    @Test
    void pinnedImageClientBypassesProxiesRejectsRedirectsAndOtherHosts() throws Exception {
        OkHttpClient client = (OkHttpClient) service.createPinnedClient("cdn.example.com", List.of(publicAddress));
        assertEquals(Proxy.NO_PROXY, client.proxy());
        assertFalse(client.followRedirects());
        assertFalse(client.followSslRedirects());
        assertEquals(List.of(publicAddress), client.dns().lookup("cdn.example.com"));
        assertThrows(UnknownHostException.class, () -> client.dns().lookup("other.example.com"));
        verifyNoInteractions(pinnedClientFactory);
    }

    private void stubResponse() throws Exception {
        when(pinnedClientFactory.create(eq("cdn.example.com"), eq(List.of(publicAddress))))
                .thenReturn(callFactory);
        when(callFactory.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response);
    }

    private byte[] pngWithDimensions(int width, int height) throws IOException {
        ByteArrayOutputStream pngBytes = new ByteArrayOutputStream();
        try (DataOutputStream png = new DataOutputStream(pngBytes)) {
            png.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});

            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            try (DataOutputStream header = new DataOutputStream(headerBytes)) {
                header.writeInt(width);
                header.writeInt(height);
                header.writeByte(8);
                header.writeByte(2);
                header.writeByte(0);
                header.writeByte(0);
                header.writeByte(0);
            }
            writeChunk(png, "IHDR", headerBytes.toByteArray());
            writeChunk(png, "IEND", new byte[0]);
        }
        return pngBytes.toByteArray();
    }

    private void writeChunk(DataOutputStream output, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        output.writeInt(data.length);
        output.write(typeBytes);
        output.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        output.writeInt((int) crc.getValue());
    }
}
