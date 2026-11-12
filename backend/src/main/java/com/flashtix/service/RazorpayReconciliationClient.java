
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Component
@Profile("dev")
@ConditionalOnProperty(name = "flashtix.razorpay.orders-enabled", havingValue = "true")
public class RazorpayReconciliationClient {

    private static final String BASE_URL = "https://api.razorpay.com/v1/orders/";

    private final HttpClient http;
    private final JsonMapper mapper;
    private final String authorization;

    public record ProviderOrder(
            String id,
            String receipt,
            long amount,
            String currency,
            String status) {
    }

    public record ProviderPayment(
            String id,
            String orderId,
            long amount,
            String currency,
            String status,
            long amountRefunded) {
    }

    public RazorpayReconciliationClient(
            JsonMapper mapper,
            @Value("${flashtix.razorpay.key-id:}") String keyId,
            @Value("${flashtix.razorpay.key-secret:}") String keySecret) {

        if (!keyId.startsWith("rzp_test_")
                || keySecret.isBlank()) {
            throw new IllegalStateException(
                    "Razorpay Test Mode credentials required");
        }

        this.mapper = mapper;

        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        authorization = "Basic "
                + Base64.getEncoder().encodeToString(
                        (keyId + ":" + keySecret)
                                .getBytes(StandardCharsets.UTF_8));
    }

    public ProviderOrder fetchOrder(String providerOrderId) {

        JsonNode root = getJson(providerOrderId, "");

        return new ProviderOrder(
                requiredText(root, "id"),
                requiredText(root, "receipt"),
                requiredLong(root, "amount"),
                requiredText(root, "currency"),
                requiredText(root, "status"));
    }

    public List<ProviderPayment> fetchPayments(
            String providerOrderId) {

        JsonNode root = getJson(providerOrderId, "/payments");
        JsonNode items = root.get("items");

        if (items == null || !items.isArray()) {
            throw new IllegalStateException(
                    "Invalid Razorpay payment collection");
        }

        List<ProviderPayment> payments = new ArrayList<>();

        for (JsonNode item : items) {
            payments.add(new ProviderPayment(
                    requiredText(item, "id"),
                    requiredText(item, "order_id"),
                    requiredLong(item, "amount"),
                    requiredText(item, "currency"),
                    requiredText(item, "status"),
                    requiredLong(item, "amount_refunded")));
        }

        // Fail closed if the provider indicates that
        // the collection was not returned completely.
        JsonNode countNode = root.get("count");

        if (countNode == null || !countNode.isIntegralNumber()
                || countNode.asLong() != payments.size()) {
            throw new IllegalStateException(
                    "Incomplete Razorpay payment collection; "
                            + "manual reconciliation required");
        }

        return List.copyOf(payments);
    }

    private JsonNode getJson(
            String providerOrderId,
            String suffix) {

        if (providerOrderId == null
                || !providerOrderId.matches(
                        "order_[A-Za-z0-9]+")) {
            throw new IllegalArgumentException(
                    "Invalid provider order ID");
        }

        URI uri = URI.create(
                BASE_URL + providerOrderId + suffix);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = http.send(
                    request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Razorpay lookup failed: HTTP "
                                + response.statusCode());
            }

            JsonNode root = mapper.readTree(response.body());

            if (root == null || !root.isObject()) {
                throw new IllegalStateException(
                        "Invalid Razorpay JSON response");
            }

            return root;

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Razorpay lookup interrupted",
                    ex);

        } catch (java.io.IOException ex) {
            throw new IllegalStateException(
                    "Razorpay lookup failed",
                    ex);
        }
    }

    private static String requiredText(
            JsonNode node,
            String field) {

        JsonNode value = node.get(field);

        if (value == null || !value.isTextual()
                || value.asText().isBlank()) {
            throw new IllegalStateException(
                    "Missing or invalid provider field: " + field);
        }

        return value.asText();
    }

    private static long requiredLong(
            JsonNode node,
            String field) {

        JsonNode value = node.get(field);

        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToLong()) {
            throw new IllegalStateException(
                    "Missing or invalid provider field: " + field);
        }

        return value.asLong();
    }
}
