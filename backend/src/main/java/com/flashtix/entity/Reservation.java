
package com.flashtix.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Column(name = "client_session_id", nullable = false)
    private UUID clientSessionId;

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "total_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPrice;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Reservation() {
        // Required by JPA
    }

    public Reservation(
            UUID id,
            Long showId,
            UUID clientSessionId,
            UUID idempotencyKey,
            String requestHash,
            BigDecimal totalPrice,
            Instant expiresAt) {

        this.id = id;
        this.showId = showId;
        this.clientSessionId = clientSessionId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.totalPrice = totalPrice;
        this.expiresAt = expiresAt;

        this.status = "HELD";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public Long getShowId() {
        return showId;
    }

    public UUID getClientSessionId() {
        return clientSessionId;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void changeStatus(String newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }
}
