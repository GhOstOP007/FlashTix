
package com.flashtix.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
@Profile("dev")
@ConditionalOnProperty(name = "flashtix.razorpay.orders-enabled", havingValue = "true")
public class RazorpayCheckoutSignatureService {

    private final byte[] secret;

    public RazorpayCheckoutSignatureService(
            @Value("${flashtix.razorpay.key-secret}") String keySecret) {

        if (keySecret == null || keySecret.isBlank()) {
            throw new IllegalStateException(
                    "Razorpay API key secret is not configured");
        }

        this.secret = keySecret.getBytes(
                StandardCharsets.UTF_8);
    }

    public void verify(
            String providerOrderId,
            String providerPaymentId,
            String signature) {

        if (providerOrderId == null
                || !providerOrderId.startsWith("order_")
                || providerPaymentId == null
                || !providerPaymentId.startsWith("pay_")
                || signature == null
                || !signature.matches("[a-fA-F0-9]{64}")) {

            throw invalidSignature();
        }

        try {
            String signedPayload = providerOrderId + "|" + providerPaymentId;

            Mac mac = Mac.getInstance("HmacSHA256");

            mac.init(
                    new SecretKeySpec(
                            secret,
                            "HmacSHA256"));

            byte[] expected = mac.doFinal(
                    signedPayload.getBytes(
                            StandardCharsets.UTF_8));

            byte[] supplied = HexFormat.of()
                    .parseHex(signature);

            if (!MessageDigest.isEqual(
                    expected,
                    supplied)) {

                throw invalidSignature();
            }

        } catch (ResponseStatusException e) {
            throw e;

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Checkout signature verification failed",
                    e);
        }
    }

    private ResponseStatusException invalidSignature() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid Razorpay Checkout signature");
    }
}
