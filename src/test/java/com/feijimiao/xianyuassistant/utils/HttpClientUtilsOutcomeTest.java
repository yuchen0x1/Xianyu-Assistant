package com.feijimiao.xianyuassistant.utils;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestOperations;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpClientUtilsOutcomeTest {

    private static final String URL = "https://local.invalid/api";

    @Test
    void timeoutIsRecordedAsAttemptedWithoutResponse() {
        RestOperations client = mock(RestOperations.class);
        when(exchange(client)).thenThrow(new ResourceAccessException(
                "timeout", new SocketTimeoutException("read timed out")));

        HttpClientUtils.HttpResponseResult result = post(client);

        assertThat(result.isRequestAttempted()).isTrue();
        assertThat(result.isResponseReceived()).isFalse();
        assertThat(result.getStatusCode()).isZero();
    }

    @Test
    void unauthorizedResponseIsAnExplicitResponse() {
        RestOperations client = mock(RestOperations.class);
        HttpClientErrorException error = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
                new byte[0], StandardCharsets.UTF_8);
        when(exchange(client)).thenThrow(error);

        HttpClientUtils.HttpResponseResult result = post(client);

        assertThat(result.isRequestAttempted()).isTrue();
        assertThat(result.isResponseReceived()).isTrue();
        assertThat(result.getStatusCode()).isEqualTo(401);
    }

    @Test
    void serverErrorResponseIsAnExplicitResponse() {
        RestOperations client = mock(RestOperations.class);
        HttpServerErrorException error = HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "Server Error", HttpHeaders.EMPTY,
                new byte[0], StandardCharsets.UTF_8);
        when(exchange(client)).thenThrow(error);

        HttpClientUtils.HttpResponseResult result = post(client);

        assertThat(result.isRequestAttempted()).isTrue();
        assertThat(result.isResponseReceived()).isTrue();
        assertThat(result.getStatusCode()).isEqualTo(500);
    }

    @Test
    void successfulResponseIsRecordedAsConfirmed() {
        RestOperations client = mock(RestOperations.class);
        when(exchange(client)).thenReturn(ResponseEntity.ok("{\"ret\":[\"SUCCESS::调用成功\"]}"));

        HttpClientUtils.HttpResponseResult result = post(client);

        assertThat(result.isRequestAttempted()).isTrue();
        assertThat(result.isResponseReceived()).isTrue();
        assertThat(result.getStatusCode()).isEqualTo(200);
    }

    private HttpClientUtils.HttpResponseResult post(RestOperations client) {
        return HttpClientUtils.postWithHeaders(client, URL, Map.of(), Map.of("data", "{}"));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<String> exchange(RestOperations client) {
        return client.exchange(
                eq(URL), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }
}
