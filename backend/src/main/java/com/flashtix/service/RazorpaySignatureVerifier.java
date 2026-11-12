
package com.flashtix.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
@Profile("dev")
public class RazorpaySignatureVerifier {

    private final byte[] secret;

    public RazorpaySignatureVerifier(
            @Value("${flashtix.razorpay.webhook-secret:}") String webhookSecret) {

        if (webhookSecret == null
                || webhookSecret.length() < 16) {

            throw new IllegalStateException(
                    "RAZORPAY_WEBHOOK_SECRET must contain " +
                            "at least 16 characters");
        }

        this.secret = webhookSecret.getBytes(
                StandardCharsets.UTF_8);
    }

    public boolean verify(
            byte[] rawBody,
            String providedSignature) {

        if (rawBody == null
                || providedSignature == null
                || !providedSignature.matches(
                        "[0-9a-fA-F]{64}")) {

            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");

            mac.init(
                    new SecretKeySpec(
                            secret,
                            "HmacSHA256"));

            byte[] expected = mac.doFinal(rawBody);

            byte[] provided = HexFormat.of().parseHex(
                    providedSignature);

            return MessageDigest.isEqual(
                    expected,
                    provided);

        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Webhook signature verification failed",
                    ex);
        }
    }
}
