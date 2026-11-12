import { useEffect, useRef, useState } from "react";

const API_BASE = "/api";
const CHECKOUT_SCRIPT = "https://checkout.razorpay.com/v1/checkout.js";

type CheckoutSuccess = {
  razorpay_payment_id: string;
  razorpay_order_id: string;
  razorpay_signature: string;
};

type RazorpayFailure = {
  error?: {
    code?: string;
    description?: string;
    reason?: string;
  };
};

type RazorpayInstance = {
  open: () => void;
  on: (
    event: "payment.failed",
    callback: (response: RazorpayFailure) => void,
  ) => void;
};

type RazorpayOptions = {
  key: string;
  amount: number;
  currency: string;
  name: string;
  description: string;
  order_id: string;
  prefill?: {
    name?: string;
    email?: string;
  };
  theme?: {
    color?: string;
  };
  modal?: {
    ondismiss?: () => void;
  };
  handler: (response: CheckoutSuccess) => void;
};

type RazorpayConstructor = new (options: RazorpayOptions) => RazorpayInstance;

declare global {
  interface Window {
    Razorpay?: RazorpayConstructor;
  }
}

export type PaymentOrder = {
  orderId: string;
  reservationId: string;
  providerOrderId: string | null;
  amountPaise: number;
  currency: string;
  status: string;
  razorpayKeyId: string;
  checkoutEligible: boolean;
};

export type FulfillmentResult = {
  paymentOrderId: string;
  reservationId: string;
  reservationStatus: string;
  paymentOrderStatus: string;
  outcome: string;
  replay: boolean;
};

type Props = {
  reservationId: string;
  accessToken: string;
  expiresAt?: string;
  customerName?: string;
  customerEmail?: string;
  onConfirmed?: (result: FulfillmentResult) => void;
};

let checkoutScriptPromise: Promise<void> | null = null;

function loadCheckoutScript(): Promise<void> {
  if (window.Razorpay) {
    return Promise.resolve();
  }

  if (checkoutScriptPromise) {
    return checkoutScriptPromise;
  }

  checkoutScriptPromise = new Promise<void>((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(
      `script[src="${CHECKOUT_SCRIPT}"]`,
    );

    const script = existing ?? document.createElement("script");

    const onLoad = () => {
      cleanup();

      if (window.Razorpay) {
        resolve();
      } else {
        reject(new Error("Razorpay Checkout did not initialize."));
      }
    };

    const onError = () => {
      cleanup();

      script.remove();

      reject(new Error("Unable to load Razorpay Checkout."));
    };

    const cleanup = () => {
      script.removeEventListener("load", onLoad);
      script.removeEventListener("error", onError);
    };

    script.addEventListener("load", onLoad);
    script.addEventListener("error", onError);

    if (!existing) {
      script.src = CHECKOUT_SCRIPT;
      script.async = true;

      document.body.appendChild(script);
    }
  }).catch((error: unknown) => {
    checkoutScriptPromise = null;
    throw error;
  });

  return checkoutScriptPromise;
}

async function requestJson<T>(
  url: string,
  accessToken: string,
  method: "GET" | "POST" = "GET",
  body?: unknown,
): Promise<T> {
  const response = await fetch(url, {
    method,
    headers: {
      Authorization: `Bearer ${accessToken}`,
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });

  if (!response.ok) {
    throw new Error(`FlashTix API returned HTTP ${response.status}`);
  }

  return (await response.json()) as T;
}

function reservationExpired(expiresAt?: string): boolean {
  if (!expiresAt) {
    return false;
  }

  const timestamp = Date.parse(expiresAt);

  return !Number.isFinite(timestamp) || timestamp <= Date.now();
}

export default function RazorpayCheckoutButton({
  reservationId,
  accessToken,
  expiresAt,
  customerName = "",
  customerEmail = "",
  onConfirmed,
}: Props) {
  const [loading, setLoading] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [message, setMessage] = useState("");
  const [needsReview, setNeedsReview] = useState(false);

  // Prevent multiple concurrent checkout attempts
  // even before React processes a state update.
  const busyRef = useRef(false);

  useEffect(() => {
    setLoading(false);
    setConfirmed(false);
    setNeedsReview(false);
    setMessage("");
    busyRef.current = false;
  }, [reservationId]);

  async function startPayment() {
    if (busyRef.current || loading || confirmed || needsReview) {
      return;
    }

    if (!reservationId || !accessToken) {
      setMessage("Please log in and reserve your seats first.");
      return;
    }

    if (reservationExpired(expiresAt)) {
      setMessage(
        "Your seat reservation has expired. " +
          "Please select your seats again.",
      );
      return;
    }

    busyRef.current = true;
    setLoading(true);
    setMessage("Preparing secure Test Mode Checkout...");

    try {
      await loadCheckoutScript();

      // A valid HELD reservation must already exist.
      // The backend checks its authoritative expiry.
      const order = await requestJson<PaymentOrder>(
        `${API_BASE}/dev/razorpay/orders`,
        accessToken,
        "POST",
        { reservationId },
      );

      if (
        !order.checkoutEligible ||
        order.status !== "CREATED" ||
        !order.providerOrderId ||
        !order.razorpayKeyId?.startsWith("rzp_test_") ||
        order.reservationId !== reservationId ||
        order.amountPaise <= 0 ||
        order.currency !== "INR"
      ) {
        throw new Error("The payment order is not eligible for Checkout.");
      }

      if (reservationExpired(expiresAt)) {
        throw new Error("Your reservation has expired. Please book again.");
      }

      if (!window.Razorpay) {
        throw new Error("Razorpay Checkout is unavailable.");
      }

      let callbackReceived = false;
      let paymentFailed = false;

      const checkout = new window.Razorpay({
        key: order.razorpayKeyId,
        amount: order.amountPaise,
        currency: order.currency,
        name: "FlashTix",
        description: "Movie ticket booking",
        order_id: order.providerOrderId,

        prefill: {
          name: customerName,
          email: customerEmail,
        },

        theme: {
          color: "#2563eb",
        },

        modal: {
          ondismiss: () => {
            if (!callbackReceived) {
              setMessage(
                paymentFailed
                  ? "The payment attempt failed. " +
                      "Check your payment status before retrying."
                  : "Checkout was closed. " +
                      "No booking confirmation has been received.",
              );

              setLoading(false);
              busyRef.current = false;
            }
          },
        },

        handler: async (response: CheckoutSuccess) => {
          callbackReceived = true;

          setMessage(
            "Payment returned by Razorpay. " +
              "Verifying and confirming your booking...",
          );

          try {
            if (response.razorpay_order_id !== order.providerOrderId) {
              throw new Error("Checkout order ID mismatch.");
            }

            const result = await requestJson<FulfillmentResult>(
              `${API_BASE}/dev/razorpay/orders/` +
                `${order.orderId}/verify-checkout`,
              accessToken,
              "POST",
              {
                razorpay_payment_id: response.razorpay_payment_id,
                razorpay_order_id: response.razorpay_order_id,
                razorpay_signature: response.razorpay_signature,
              },
            );

            if (
              result.reservationStatus === "CONFIRMED" &&
              result.paymentOrderStatus === "SUCCEEDED" &&
              result.reservationId === reservationId
            ) {
              setConfirmed(true);
              setMessage("Payment verified! Your tickets are confirmed.");

              onConfirmed?.(result);
            } else {
              setNeedsReview(true);
              setMessage(
                "Payment was reported, but your seats " +
                  "could not be confirmed. " +
                  "Do not pay again. Check My Bookings " +
                  "and contact support.",
              );
            }
          } catch {
            setNeedsReview(true);
            setMessage(
              "Razorpay returned a payment, but FlashTix " +
                "could not verify the booking result. " +
                "Do not make another payment. " +
                "Check My Bookings and contact support.",
            );
          } finally {
            setLoading(false);
            busyRef.current = false;
          }
        },
      });

      checkout.on("payment.failed", (response: RazorpayFailure) => {
        paymentFailed = true;

        // A failed attempt is not proof that the
        // reservation has been cancelled.
        setMessage(
          response.error?.description ||
            "Payment failed. Check the payment " +
              "status before trying again.",
        );
      });

      setMessage("Complete your Razorpay test payment.");

      checkout.open();
    } catch (error) {
      setMessage(
        error instanceof Error
          ? error.message
          : "Unable to start Razorpay Checkout.",
      );

      setLoading(false);
      busyRef.current = false;
    }
  }

  return (
    <div className="razorpay-checkout">
      <button
        type="button"
        onClick={startPayment}
        disabled={loading || confirmed || needsReview}
        style={{
          padding: "12px 20px",
          borderRadius: "8px",
          backgroundColor: confirmed ? "#16a34a" : "#2563eb",
          color: "#ffffff",
          border: "none",
          fontSize: "14px",
          fontWeight: 600,
          cursor:
            loading || confirmed || needsReview ? "not-allowed" : "pointer",
          opacity: loading || confirmed || needsReview ? 0.7 : 1,
        }}
      >
        {confirmed
          ? "Booking Confirmed"
          : needsReview
            ? "Payment Under Review"
            : loading
              ? "Processing..."
              : "Pay with Razorpay (Test Mode)"}
      </button>

      {message && (
        <p
          role="status"
          aria-live="polite"
          style={{
            marginTop: "10px",
            fontSize: "13px",
            lineHeight: 1.5,
            color: needsReview ? "#b45309" : "inherit",
          }}
        >
          {message}
        </p>
      )}
    </div>
  );
}
