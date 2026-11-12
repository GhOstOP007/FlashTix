
package com.flashtix.service;

public final class PaymentReconciliationRules {

    private PaymentReconciliationRules() {
    }

    public enum Decision {
        UNPAID,
        PAYMENT_PENDING,
        CAPTURED_AWAITING_FULFILLMENT,
        LATE_CAPTURE_REVIEW,
        ALREADY_FULFILLED,
        REQUIRES_INVESTIGATION
    }

    public static Decision classify(
            String reservationStatus,
            boolean reservationActive,
            String localOrderStatus,
            String providerOrderStatus,
            boolean capturedPaymentFound,
            boolean paymentEvidenceMismatch) {

        // Never automatically fulfil payments when
        // the provider evidence is inconsistent.
        if (paymentEvidenceMismatch) {
            return Decision.REQUIRES_INVESTIGATION;
        }

        if (capturedPaymentFound) {

            if ("CONFIRMED".equals(reservationStatus)
                    && "SUCCEEDED".equals(localOrderStatus)) {
                return Decision.ALREADY_FULFILLED;
            }

            if ("HELD".equals(reservationStatus)
                    && reservationActive) {
                return Decision.CAPTURED_AWAITING_FULFILLMENT;
            }

            return Decision.LATE_CAPTURE_REVIEW;
        }

        // A locally confirmed reservation without
        // evidence of payment is suspicious.
        if ("CONFIRMED".equals(reservationStatus)
                || "SUCCEEDED".equals(localOrderStatus)
                || "paid".equals(providerOrderStatus)) {
            return Decision.REQUIRES_INVESTIGATION;
        }

        if ("attempted".equals(providerOrderStatus)) {
            return Decision.PAYMENT_PENDING;
        }

        return Decision.UNPAID;
    }
}
