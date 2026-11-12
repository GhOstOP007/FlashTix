
package com.flashtix.service;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
@Profile("dev")
public class RazorpayWebhookService {

    private static final String PROVIDER = "RAZORPAY";
    private static final int MAX_BODY_SIZE = 256 * 1024;

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final RazorpaySignatureVerifier verifier;

    public RazorpayWebhookService(
            JdbcTemplate jdbc,
            JsonMapper jsonMapper,
            RazorpaySignatureVerifier verifier) {

        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.verifier = verifier;
    }

    public record WebhookReceipt(
            boolean accepted,
            boolean duplicate) {
    }

    @Transactional
    public WebhookReceipt receive(
            byte[] rawBody,
            String signature,
            String eventId) {

        // 1. Check request size before processing.
        if (rawBody == null
                || rawBody.length == 0
                || rawBody.length > MAX_BODY_SIZE) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid webhook body size");
        }

        // 2. Verify signature BEFORE parsing JSON.
        if (!verifier.verify(rawBody, signature)) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid webhook signature");
        }

        // 3. Validate the provider event identifier.
        if (eventId == null
                || eventId.isBlank()
                || eventId.length() > 160) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid webhook event ID");
        }

        // 4. Parse only after successful verification.
        String eventType;

        try {
            JsonNode root = jsonMapper.readTree(rawBody);

            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException(
                        "Webhook payload must be a JSON object");
            }

            JsonNode eventNode = root.get("event");

            if (eventNode == null
                    || !eventNode.isTextual()) {

                throw new IllegalArgumentException(
                        "Missing event type");
            }

            eventType = eventNode.asText();

        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Malformed webhook payload",
                    ex);
        }

        if (eventType.isBlank()
                || eventType.length() > 100) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid webhook event type");
        }

        // 5. Digest of original verified bytes.
        String payloadHash = sha256(rawBody);

        UUID internalId = UUID.randomUUID();

        // 6. Atomic duplicate handling.
        // If another transaction inserted the same
        // event ID, PostgreSQL handles the conflict.
        int inserted = jdbc.update("""
                INSERT INTO payment_webhook_events (
                    id,
                    provider,
                    provider_event_id,
                    event_type,
                    payload_sha256,
                    processing_status
                )
                VALUES (?, ?, ?, ?, ?, 'RECEIVED')
                ON CONFLICT (
                    provider,
                    provider_event_id
                )
                DO NOTHING
                """,
                internalId,
                PROVIDER,
                eventId,
                eventType,
                payloadHash);

        if (inserted == 1) {
            return new WebhookReceipt(true, false);
        }

        // 7. Compare repeated event with the
        // previously persisted payload digest.
        String storedHash = jdbc.queryForObject("""
                SELECT payload_sha256
                FROM payment_webhook_events
                WHERE provider = ?
                  AND provider_event_id = ?
                """,
                String.class,
                PROVIDER,
                eventId);

        if (!payloadHash.equals(storedHash)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Event ID was reused with different payload");
        }

        return new WebhookReceipt(true, true);
    }

    private String sha256(byte[] input) {
        try {
            byte[] hash = MessageDigest
                    .getInstance("SHA-256")
                    .digest(input);

            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    ex);
        }
    }
}
