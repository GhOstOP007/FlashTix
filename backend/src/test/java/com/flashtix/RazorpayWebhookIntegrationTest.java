
package com.flashtix;

import com.flashtix.service.RazorpayWebhookService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "flashtix.jwt.secret-base64=" +
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "flashtix.razorpay.webhook-secret=" +
                "flashtix-test-webhook-secret-2026"
})
@ActiveProfiles("dev")
@Testcontainers
class RazorpayWebhookIntegrationTest {

    private static final String SECRET = "flashtix-test-webhook-secret-2026";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    private RazorpayWebhookService webhookService;

    @Autowired
    private JdbcTemplate jdbc;

    private byte[] payload() {
        return """
                {
                  "entity": "event",
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "pay_test123",
                        "status": "captured"
                      }
                    }
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
    }

    private String sign(byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");

            mac.init(
                    new SecretKeySpec(
                            SECRET.getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256"));

            return HexFormat.of().formatHex(
                    mac.doFinal(rawBody));

        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---------------------------------------
    // TEST 1 — Valid signature
    // ---------------------------------------

    @Test
    void validSignatureStoresWebhook() {

        byte[] body = payload();
        String eventId = UUID.randomUUID().toString();

        var receipt = webhookService.receive(
                body,
                sign(body),
                eventId);

        assertTrue(receipt.accepted());
        assertFalse(receipt.duplicate());

        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM payment_webhook_events
                WHERE provider = 'RAZORPAY'
                  AND provider_event_id = ?
                  AND processing_status = 'RECEIVED'
                """,
                Integer.class,
                eventId);

        assertEquals(1, count);
    }

    // ---------------------------------------
    // TEST 2 — Invalid signature
    // ---------------------------------------

    @Test
    void invalidSignatureIsRejected() {

        byte[] body = payload();

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> webhookService.receive(
                        body,
                        "0".repeat(64),
                        UUID.randomUUID().toString()));

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                error.getStatusCode());
    }

    // ---------------------------------------
    // TEST 3 — Modified payload
    // ---------------------------------------

    @Test
    void modifiedPayloadIsRejected() {

        byte[] original = payload();
        String signature = sign(original);

        byte[] modified = new String(
                original,
                StandardCharsets.UTF_8).replace(
                        "payment.captured",
                        "payment.failed")
                .getBytes(StandardCharsets.UTF_8);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> webhookService.receive(
                        modified,
                        signature,
                        UUID.randomUUID().toString()));

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                error.getStatusCode());
    }

    // ---------------------------------------
    // TEST 4 — Duplicate event
    // ---------------------------------------

    @Test
    void duplicateWebhookCreatesOnlyOneRecord() {

        byte[] body = payload();
        String eventId = UUID.randomUUID().toString();
        String signature = sign(body);

        var first = webhookService.receive(
                body,
                signature,
                eventId);

        var second = webhookService.receive(
                body,
                signature,
                eventId);

        assertFalse(first.duplicate());
        assertTrue(second.duplicate());

        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM payment_webhook_events
                WHERE provider = 'RAZORPAY'
                  AND provider_event_id = ?
                """,
                Integer.class,
                eventId);

        assertEquals(1, count);
    }

    // ---------------------------------------
    // TEST 5 — Event ID reused with new payload
    // ---------------------------------------

    @Test
    void sameEventIdWithDifferentPayloadIsRejected() {

        String eventId = UUID.randomUUID().toString();

        byte[] first = payload();

        webhookService.receive(
                first,
                sign(first),
                eventId);

        byte[] different = new String(
                first,
                StandardCharsets.UTF_8).replace(
                        "payment.captured",
                        "payment.failed")
                .getBytes(StandardCharsets.UTF_8);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> webhookService.receive(
                        different,
                        sign(different),
                        eventId));

        assertEquals(
                HttpStatus.CONFLICT,
                error.getStatusCode());
    }
}
