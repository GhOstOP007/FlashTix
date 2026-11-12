
package com.flashtix.repository;

import com.flashtix.entity.PaymentWebhookEvent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentWebhookEventRepository
        extends JpaRepository<PaymentWebhookEvent, UUID> {

    Optional<PaymentWebhookEvent> findByProviderAndProviderEventId(
            String provider,
            String providerEventId);

    boolean existsByProviderAndProviderEventId(
            String provider,
            String providerEventId);
}
