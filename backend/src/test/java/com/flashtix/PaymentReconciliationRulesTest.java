
package com.flashtix;

import com.flashtix.service.PaymentReconciliationRules;
import com.flashtix.service.PaymentReconciliationRules.Decision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentReconciliationRulesTest {

    @Test
    void unpaidOrderStaysUnpaid() {

        Decision result = PaymentReconciliationRules.classify(
                "HELD",
                true,
                "CREATED",
                "created",
                false,
                false);

        assertEquals(Decision.UNPAID, result);
    }

    @Test
    void attemptedOrderIsNotCaptured() {

        Decision result = PaymentReconciliationRules.classify(
                "HELD",
                true,
                "CREATED",
                "attempted",
                false,
                false);

        assertEquals(Decision.PAYMENT_PENDING, result);
    }

    @Test
    void capturedPaymentOnActiveHoldAwaitsFulfillment() {

        Decision result = PaymentReconciliationRules.classify(
                "HELD",
                true,
                "CREATED",
                "paid",
                true,
                false);

        assertEquals(
                Decision.CAPTURED_AWAITING_FULFILLMENT,
                result);
    }

    @Test
    void capturedPaymentOnExpiredHoldNeedsReview() {

        Decision result = PaymentReconciliationRules.classify(
                "EXPIRED",
                false,
                "CREATED",
                "paid",
                true,
                false);

        assertEquals(
                Decision.LATE_CAPTURE_REVIEW,
                result);
    }

    @Test
    void fulfilledOrderIsRecognized() {

        Decision result = PaymentReconciliationRules.classify(
                "CONFIRMED",
                false,
                "SUCCEEDED",
                "paid",
                true,
                false);

        assertEquals(
                Decision.ALREADY_FULFILLED,
                result);
    }

    @Test
    void inconsistentPaymentEvidenceNeedsInvestigation() {

        Decision result = PaymentReconciliationRules.classify(
                "HELD",
                true,
                "CREATED",
                "paid",
                true,
                true);

        assertEquals(
                Decision.REQUIRES_INVESTIGATION,
                result);
    }

    @Test
    void localConfirmationWithoutCaptureNeedsInvestigation() {

        Decision result = PaymentReconciliationRules.classify(
                "CONFIRMED",
                false,
                "SUCCEEDED",
                "attempted",
                false,
                false);

        assertEquals(
                Decision.REQUIRES_INVESTIGATION,
                result);
    }
}
