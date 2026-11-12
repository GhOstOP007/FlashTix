
package com.flashtix.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

@Component
@Profile("dev")
@ConditionalOnProperty(name = "flashtix.razorpay.orders-enabled", havingValue = "true")
public class RazorpayOrderClient {

        private static final URI CREATE_ORDER_URI = URI.create("https://api.razorpay.com/v1/orders");

        private final HttpClient httpClient;
        private final JsonMapper jsonMapper;
        private final String authorization;

        public record ProviderOrder(
                        String id,
                        long amountPaise,
                        String currency,
                        String receipt,
                        String status) {
        }

        public RazorpayOrderClient(
                        JsonMapper jsonMapper,
                        @Value("${flashtix.razorpay.key-id:}") String keyId,
                        @Value("${flashtix.razorpay.key-secret:}") String keySecret) {

                if (!keyId.startsWith("rzp_test_")
                                || keySecret.isBlank()) {
                        throw new IllegalStateException(
                                        "Valid Razorpay Test Mode credentials are required");
                }

                this.jsonMapper = jsonMapper;

                this.httpClient = HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(5))
                                .build();

                String credentials = keyId + ":" + keySecret;

                this.authorization = "Basic " +
                                Base64.getEncoder().encodeToString(
                                                credentials.getBytes(StandardCharsets.UTF_8));
        }

        public ProviderOrder createOrder(
                        long amountPaise,
                        String receipt) {

                try {
                        String json = jsonMapper.writeValueAsString(
                                        Map.of(
                                                        "amount", amountPaise,
                                                        "currency", "INR",
                                                        "receipt", receipt));

                        HttpRequest request = HttpRequest.newBuilder()
                                        .uri(CREATE_ORDER_URI)
                                        .timeout(Duration.ofSeconds(12))
                                        .header("Authorization", authorization)
                                        .header("Content-Type", "application/json")
                                        .POST(
                                                        HttpRequest.BodyPublishers.ofString(json))
                                        .build();

                        HttpResponse<String> response = httpClient.send(
                                        request,
                                        HttpResponse.BodyHandlers.ofString());

                        if (response.statusCode() != 200) {
                                throw new IllegalStateException(
                                                "Razorpay order creation failed with HTTP "
                                                                + response.statusCode());
                        }

                        JsonNode root = jsonMapper.readTree(
                                        response.body());

                        if (root == null || !root.isObject()) {
                                throw new IllegalStateException(
                                                "Invalid Razorpay response");
                        }

                        JsonNode idNode = root.get("id");
                        JsonNode amountNode = root.get("amount");
                        JsonNode currencyNode = root.get("currency");
                        JsonNode receiptNode = root.get("receipt");
                        JsonNode statusNode = root.get("status");

                        if (idNode == null
                                        || amountNode == null
                                        || currencyNode == null
                                        || receiptNode == null
                                        || statusNode == null) {
                                throw new IllegalStateException(
                                                "Incomplete Razorpay order response");
                        }

                        ProviderOrder order = new ProviderOrder(
                                        idNode.asText(),
                                        amountNode.asLong(),
                                        currencyNode.asText(),
                                        receiptNode.asText(),
                                        statusNode.asText());

                        if (!order.id().startsWith("order_")
                                        || order.id().length() > 160
                                        || order.amountPaise() != amountPaise
                                        || !"INR".equals(order.currency())
                                        || !receipt.equals(order.receipt())
                                        || !"created".equals(order.status())) {

                                throw new IllegalStateException(
                                                "Razorpay order response validation failed");
                        }

                        return order;

                } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();

                        throw new IllegalStateException(
                                        "Razorpay request interrupted; reconciliation required",
                                        ex);

                } catch (Exception ex) {
                        throw new IllegalStateException(
                                        "Razorpay order result is uncertain; reconciliation required",
                                        ex);
                }
        }
}
