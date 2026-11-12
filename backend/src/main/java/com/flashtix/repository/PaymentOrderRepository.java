
package com.flashtix.repository;

import com.flashtix.entity.PaymentOrder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentOrderRepository
        extends JpaRepository<PaymentOrder, UUID> {

    Optional<PaymentOrder> findByReservationId(
            UUID reservationId);

    List<PaymentOrder> findByUserIdOrderByCreatedAtDesc(
            UUID userId);

    Optional<PaymentOrder> findByProviderAndProviderOrderId(
            String provider,
            String providerOrderId);
}
