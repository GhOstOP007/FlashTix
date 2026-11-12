
package com.flashtix.repository;

import com.flashtix.entity.PaymentAttempt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository
        extends JpaRepository<PaymentAttempt, UUID> {

    List<PaymentAttempt> findByPaymentOrderIdOrderByCreatedAtDesc(
            UUID paymentOrderId);

    Optional<PaymentAttempt> findByProviderAndProviderPaymentId(
            String provider,
            String providerPaymentId);
}
