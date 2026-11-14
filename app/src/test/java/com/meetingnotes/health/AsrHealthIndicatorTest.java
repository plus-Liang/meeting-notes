package com.meetingnotes.health;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** T2.4：ASR 探活——GET {base}/health，2s 独立超时；2xx=UP，任何异常=DOWN（不抛出）。 */
class AsrHealthIndicatorTest {

    private static RestClient deepStubClient() {
        return Mockito.mock(RestClient.class, Mockito.RETURNS_DEEP_STUBS);
    }

    @Test
    void asrReachableReportsUp() {
        RestClient restClient = deepStubClient();
        when(restClient.get().uri("http://asr:8000/health").retrieve().toBodilessEntity())
                .thenReturn(ResponseEntity.ok().build());

        Health health = new AsrHealthIndicator(restClient, "http://asr:8000").health();

        assertEquals(Status.UP, health.getStatus());
    }

    @Test
    void asrErrorOrTimeoutReportsDownWithoutThrowing() {
        RestClient restClient = deepStubClient();
        when(restClient.get().uri("http://asr:8000/health").retrieve().toBodilessEntity())
                .thenThrow(new ResourceAccessException("simulated timeout"));

        Health health = new AsrHealthIndicator(restClient, "http://asr:8000").health();

        assertEquals(Status.DOWN, health.getStatus());
    }

    @Test
    void asrNon2xxReportsDown() {
        RestClient restClient = deepStubClient();
        when(restClient.get().uri("http://asr:8000/health").retrieve().toBodilessEntity())
                .thenThrow(new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));

        Health health = new AsrHealthIndicator(restClient, "http://asr:8000").health();

        assertEquals(Status.DOWN, health.getStatus());
    }
}
