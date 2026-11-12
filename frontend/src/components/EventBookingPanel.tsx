import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Armchair,
  CalendarDays,
  Clock3,
  LockKeyhole,
  RefreshCw,
  Ticket,
  XCircle,
} from "lucide-react";

import { useAuth } from "../context/AuthContext";
import RazorpayCheckoutButton from "./RazorpayCheckoutButton";
import type { FulfillmentResult } from "./RazorpayCheckoutButton";
import "./EventBookingPanel.css";

type SeatStatus = "AVAILABLE" | "HELD" | "BOOKED" | "BLOCKED";

interface Show {
  id: number;
  eventId: number;
  venueId: number;
  startTime: string;
  status: string;
  totalSeats: number;
}

interface ShowSeat {
  id: number;
  seatId: number;
  rowLabel: string;
  seatNumber: number;
  seatType: string;
  price: number;
  status: SeatStatus;
}

interface Reservation {
  reservationId: string;
  showId: number;
  showSeatIds: number[];
  status: string;
  totalPrice: number;
  expiresAt: string;
  createdAt: string;
}

interface EventBookingPanelProps {
  eventId: number;
  onRequireAuth: () => void;
}

interface PendingAttempt {
  fingerprint: string;
  idempotencyKey: string;
}

const MAX_SEATS = 6;

function formatCurrency(value: number) {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
  }).format(value);
}

function formatShowTime(value: string) {
  return new Intl.DateTimeFormat("en-IN", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "Asia/Kolkata",
  }).format(new Date(value));
}

function formatCountdown(milliseconds: number) {
  const secondsLeft = Math.max(0, Math.ceil(milliseconds / 1000));
  const minutes = Math.floor(secondsLeft / 60);
  const seconds = secondsLeft % 60;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

function reservationKey(userId: string, showId: number) {
  return `flashtix:reservation:${userId}:${showId}`;
}

function attemptKey(userId: string, showId: number) {
  return `flashtix:attempt:${userId}:${showId}`;
}

async function apiError(response: Response): Promise<string> {
  try {
    const data: unknown = await response.json();
    if (data && typeof data === "object") {
      const details = data as Record<string, unknown>;
      if (typeof details.detail === "string") return details.detail;
      if (typeof details.message === "string") return details.message;
    }
  } catch {
    // Default to the status code when no JSON error body exists.
  }
  return `Request failed (HTTP ${response.status})`;
}

function readAttempt(key: string): PendingAttempt | null {
  try {
    const raw = sessionStorage.getItem(key);
    if (!raw) return null;
    const value: unknown = JSON.parse(raw);
    if (!value || typeof value !== "object") return null;
    const attempt = value as Record<string, unknown>;
    if (
      typeof attempt.fingerprint !== "string" ||
      typeof attempt.idempotencyKey !== "string"
    )
      return null;
    return {
      fingerprint: attempt.fingerprint,
      idempotencyKey: attempt.idempotencyKey,
    };
  } catch {
    return null;
  }
}

export default function EventBookingPanel({
  eventId,
  onRequireAuth,
}: EventBookingPanelProps) {
  const { user, accessToken, isAuthenticated, logout } = useAuth();

  const [shows, setShows] = useState<Show[]>([]);
  const [selectedShowId, setSelectedShowId] = useState<number | null>(null);
  const [seats, setSeats] = useState<ShowSeat[]>([]);
  const [selectedSeatIds, setSelectedSeatIds] = useState<number[]>([]);
  const [reservation, setReservation] = useState<Reservation | null>(null);
  const [confirmedReservationId, setConfirmedReservationId] = useState<
    string | null
  >(null);
  const [loadingShows, setLoadingShows] = useState(true);
  const [loadingSeats, setLoadingSeats] = useState(false);
  const [restoring, setRestoring] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [now, setNow] = useState(Date.now());

  const userId = user?.userId ?? null;

  const refreshSeats = useCallback(() => {
    setRefreshKey((previous) => previous + 1);
  }, []);

  // 1. Load available showtimes for the selected event.
  useEffect(() => {
    const controller = new AbortController();
    setShows([]);
    setSelectedShowId(null);
    setError(null);
    setLoadingShows(true);

    async function loadShows() {
      try {
        const response = await fetch(`/api/shows?eventId=${eventId}`, {
          signal: controller.signal,
        });
        if (!response.ok) throw new Error(await apiError(response));
        const data: Show[] = await response.json();
        if (!Array.isArray(data))
          throw new Error("Invalid showtimes response.");
        if (controller.signal.aborted) return;
        setShows(data);
        setSelectedShowId(data.length ? data[0].id : null);
      } catch (err) {
        if (!controller.signal.aborted) {
          setError(
            err instanceof Error ? err.message : "Unable to load shows.",
          );
        }
      } finally {
        if (!controller.signal.aborted) setLoadingShows(false);
      }
    }

    void loadShows();
    return () => controller.abort();
  }, [eventId]);

  // 2. Load seat availability, including after bookings/cancellations.
  useEffect(() => {
    if (selectedShowId === null) {
      setSeats([]);
      return;
    }
    const controller = new AbortController();
    const showId = selectedShowId;
    setLoadingSeats(true);

    async function loadSeats() {
      try {
        const response = await fetch(`/api/shows/${showId}/seats`, {
          signal: controller.signal,
        });
        if (!response.ok) throw new Error(await apiError(response));
        const data: ShowSeat[] = await response.json();
        if (!Array.isArray(data))
          throw new Error("Invalid seat availability response.");
        if (!controller.signal.aborted) {
          setSeats(data);
          // Deselect seats that another customer has reserved.
          setSelectedSeatIds((ids) =>
            ids.filter((id) =>
              data.some((s) => s.id === id && s.status === "AVAILABLE"),
            ),
          );
        }
      } catch (err) {
        if (!controller.signal.aborted) {
          setError(
            err instanceof Error ? err.message : "Unable to load seats.",
          );
        }
      } finally {
        if (!controller.signal.aborted) setLoadingSeats(false);
      }
    }

    void loadSeats();
    return () => controller.abort();
  }, [selectedShowId, refreshKey]);

  // 3. Restore an existing held reservation from this browser session.
  useEffect(() => {
    let cancelled = false;
    setReservation(null);
    setSelectedSeatIds([]);
    setRestoring(false);
    setConfirmedReservationId(null);

    if (selectedShowId === null || !userId || !accessToken) return;

    const showId = selectedShowId;
    const key = reservationKey(userId, showId);
    const savedId = sessionStorage.getItem(key);
    if (!savedId) return;
    setRestoring(true);

    async function restore() {
      try {
        const response = await fetch(
          `/api/reservations/${encodeURIComponent(savedId!)}`,
          {
            headers: { Authorization: `Bearer ${accessToken}` },
          },
        );
        if (cancelled) return;

        if (!response.ok) {
          if (response.status === 404 || response.status === 410) {
            sessionStorage.removeItem(key);
          }
          return;
        }

        const data: Reservation = await response.json();
        if (cancelled) return;
        if (data.status === "HELD" && Date.parse(data.expiresAt) > Date.now()) {
          setReservation(data);
          refreshSeats();
        } else {
          sessionStorage.removeItem(key);
          if (data.status === "CONFIRMED") {
            setConfirmedReservationId(data.reservationId);
            setMessage(
              "Your booking is already confirmed. View it in My Bookings.",
            );
          }
        }
      } catch {
        // Keep the saved reservation ID for the next attempt.
      } finally {
        if (!cancelled) setRestoring(false);
      }
    }

    void restore();
    return () => {
      cancelled = true;
    };
  }, [selectedShowId, userId, accessToken, refreshSeats]);

  // 4. Countdown for the temporary five-minute hold.
  useEffect(() => {
    const interval = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(interval);
  }, []);

  useEffect(() => {
    if (!reservation || Date.parse(reservation.expiresAt) > now) return;
    if (userId) {
      sessionStorage.removeItem(reservationKey(userId, reservation.showId));
    }
    setReservation(null);
    setSelectedSeatIds([]);
    setMessage(
      "Your seat hold has expired. If you started a payment, check My Bookings before paying again.",
    );
    refreshSeats();
  }, [reservation, now, userId, refreshSeats]);

  // 5. Build the seat map and selected total.
  const seatRows = useMemo(() => {
    const rows = new Map<string, ShowSeat[]>();
    for (const seat of seats) {
      const group = rows.get(seat.rowLabel) ?? [];
      group.push(seat);
      rows.set(seat.rowLabel, group);
    }
    return Array.from(rows.entries())
      .sort(([a], [b]) => a.localeCompare(b, undefined, { numeric: true }))
      .map(([label, rowSeats]) => ({
        label,
        seats: rowSeats.sort((a, b) => a.seatNumber - b.seatNumber),
      }));
  }, [seats]);

  const selectedSeats = useMemo(
    () => seats.filter((seat) => selectedSeatIds.includes(seat.id)),
    [seats, selectedSeatIds],
  );
  const totalPrice = selectedSeats.reduce(
    (sum, seat) => sum + Number(seat.price),
    0,
  );
  const remainingMs = reservation
    ? Math.max(0, Date.parse(reservation.expiresAt) - now)
    : 0;

  function toggleSeat(seat: ShowSeat) {
    if (
      seat.status !== "AVAILABLE" ||
      reservation ||
      restoring ||
      submitting ||
      cancelling ||
      confirmedReservationId
    )
      return;
    setError(null);
    setMessage(null);
    setSelectedSeatIds((previous) => {
      if (previous.includes(seat.id))
        return previous.filter((id) => id !== seat.id);
      if (previous.length >= MAX_SEATS) return previous;
      return [...previous, seat.id];
    });
  }

  function changeShow(showId: number) {
    if (submitting || cancelling || restoring || reservation) return;
    setSelectedShowId(showId);
    setSeats([]);
    setSelectedSeatIds([]);
    setError(null);
    setMessage(null);
    setConfirmedReservationId(null);
  }

  function requireLogin() {
    setError(null);
    onRequireAuth();
  }

  // 6. Create an authenticated reservation with an idempotency key.
  async function reserveSeats() {
    if (!isAuthenticated || !accessToken || !user) {
      requireLogin();
      return;
    }
    if (
      selectedShowId === null ||
      selectedSeatIds.length === 0 ||
      submitting ||
      restoring ||
      reservation ||
      confirmedReservationId
    )
      return;

    const showId = selectedShowId;
    const sortedIds = [...selectedSeatIds].sort((a, b) => a - b);
    const fingerprint = `${showId}:${sortedIds.join(",")}`;
    const key = attemptKey(user.userId, showId);
    const existingAttempt = readAttempt(key);
    const idempotencyKey =
      existingAttempt?.fingerprint === fingerprint
        ? existingAttempt.idempotencyKey
        : crypto.randomUUID();

    sessionStorage.setItem(
      key,
      JSON.stringify({ fingerprint, idempotencyKey }),
    );
    setSubmitting(true);
    setError(null);
    setMessage(null);

    try {
      const response = await fetch("/api/reservations", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${accessToken}`,
        },
        body: JSON.stringify({
          idempotencyKey,
          showId,
          showSeatIds: sortedIds,
        }),
      });

      if (response.status === 401) {
        logout();
        throw new Error("Your login has expired. Please sign in again.");
      }
      if (!response.ok) {
        if (response.status === 409) {
          sessionStorage.removeItem(key);
          refreshSeats();
        }
        throw new Error(await apiError(response));
      }

      const created: Reservation = await response.json();
      if (
        !created.reservationId ||
        created.status !== "HELD" ||
        Date.parse(created.expiresAt) <= Date.now()
      )
        throw new Error(
          "Reservation was not held successfully. Check My Bookings.",
        );

      sessionStorage.setItem(
        reservationKey(user.userId, created.showId),
        created.reservationId,
      );
      sessionStorage.removeItem(key);
      setReservation(created);
      setSelectedSeatIds([]);
      setNow(Date.now());
      setMessage("Seats reserved! Complete payment before the hold expires.");
      refreshSeats();
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Unable to create reservation.",
      );
    } finally {
      setSubmitting(false);
    }
  }

  // 7. Cancel a reservation only when the user has not started payment.
  async function cancelReservation() {
    if (!reservation || !accessToken || !user || cancelling) return;
    const current = reservation;
    setCancelling(true);
    setError(null);
    setMessage(null);

    try {
      const response = await fetch(
        `/api/reservations/${encodeURIComponent(current.reservationId)}/cancel`,
        {
          method: "POST",
          headers: { Authorization: `Bearer ${accessToken}` },
        },
      );
      if (response.status === 401) {
        logout();
        throw new Error("Your login has expired. Please sign in again.");
      }
      if (!response.ok) throw new Error(await apiError(response));

      sessionStorage.removeItem(reservationKey(user.userId, current.showId));
      setReservation(null);
      setSelectedSeatIds([]);
      setMessage("Reservation cancelled. Seats are now available.");
      refreshSeats();
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Unable to cancel reservation.",
      );
    } finally {
      setCancelling(false);
    }
  }

  function handlePaymentConfirmed(result: FulfillmentResult) {
    if (!reservation || result.reservationId !== reservation.reservationId)
      return;
    if (
      result.reservationStatus !== "CONFIRMED" ||
      result.paymentOrderStatus !== "SUCCEEDED"
    )
      return;

    if (userId)
      sessionStorage.removeItem(reservationKey(userId, reservation.showId));
    setConfirmedReservationId(result.reservationId);
    setReservation(null);
    setSelectedSeatIds([]);
    setError(null);
    setMessage(
      "Payment verified. Your tickets are confirmed! Open My Bookings to view them.",
    );
    refreshSeats();
  }

  // 8. Render.
  return (
    <div className="booking-panel">
      <div className="booking-heading">
        <Ticket size={20} />
        <h3>Book Your Seats</h3>
      </div>

      {!isAuthenticated && (
        <div className="booking-auth-prompt">
          <LockKeyhole size={18} />
          <span>Sign in to reserve seats for this event.</span>
          <button type="button" onClick={requireLogin}>
            Sign In
          </button>
        </div>
      )}

      {error && (
        <div className="booking-error" role="alert">
          {error}
        </div>
      )}
      {message && (
        <div className="booking-notice" role="status">
          {message}
        </div>
      )}

      <div className="booking-show-section">
        <h4>
          <CalendarDays size={17} /> Choose Your Show
        </h4>
        {loadingShows ? (
          <p className="booking-muted">Loading showtimes...</p>
        ) : shows.length === 0 ? (
          <p className="booking-empty">No upcoming shows are available.</p>
        ) : (
          <div className="show-options">
            {shows.map((show) => (
              <button
                key={show.id}
                type="button"
                disabled={
                  submitting || cancelling || restoring || reservation !== null
                }
                className={
                  selectedShowId === show.id
                    ? "show-option active"
                    : "show-option"
                }
                onClick={() => changeShow(show.id)}
              >
                <CalendarDays size={16} />
                <span>{formatShowTime(show.startTime)}</span>
              </button>
            ))}
          </div>
        )}
      </div>

      {selectedShowId !== null && (
        <>
          <div className="seat-section-heading">
            <h4>
              <Armchair size={18} /> Select Your Seats
            </h4>
            <button
              type="button"
              className="refresh-seats"
              disabled={loadingSeats}
              onClick={refreshSeats}
            >
              <RefreshCw
                size={16}
                className={loadingSeats ? "spin-icon" : ""}
              />
              Refresh
            </button>
          </div>

          <p className="booking-muted">
            Select up to {MAX_SEATS} available seats.
          </p>
          <div className="stage-area">
            <div className="stage-line" />
            <span>STAGE / SCREEN</span>
          </div>

          {loadingSeats ? (
            <p className="booking-muted">Loading seats...</p>
          ) : seatRows.length === 0 ? (
            <p className="booking-empty">No seats found for this show.</p>
          ) : (
            <div className="seat-map-scroll">
              <div className="seat-map">
                {seatRows.map((row) => (
                  <div className="seat-row" key={row.label}>
                    <span className="seat-row-label">{row.label}</span>
                    {row.seats.map((seat) => {
                      const selected = selectedSeatIds.includes(seat.id);
                      const unavailable = seat.status !== "AVAILABLE";
                      return (
                        <button
                          key={seat.id}
                          type="button"
                          disabled={
                            unavailable ||
                            submitting ||
                            cancelling ||
                            restoring ||
                            reservation !== null ||
                            confirmedReservationId !== null
                          }
                          className={[
                            "seat-button",
                            selected ? "selected" : "",
                            unavailable ? seat.status.toLowerCase() : "",
                          ]
                            .filter(Boolean)
                            .join(" ")}
                          onClick={() => toggleSeat(seat)}
                          title={`${seat.rowLabel}${seat.seatNumber} - ${seat.status} - ${formatCurrency(Number(seat.price))}`}
                          aria-label={`Seat ${seat.rowLabel}${seat.seatNumber}, ${seat.status}`}
                          aria-pressed={selected}
                        >
                          {seat.seatNumber}
                        </button>
                      );
                    })}
                    <span className="seat-row-label">{row.label}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          <div className="seat-legend">
            <span>
              <i className="legend-dot available" /> Available
            </span>
            <span>
              <i className="legend-dot selected" /> Selected
            </span>
            <span>
              <i className="legend-dot held" /> Held
            </span>
            <span>
              <i className="legend-dot booked" /> Booked
            </span>
          </div>

          {restoring ? (
            <p className="booking-muted">
              Checking your existing reservation...
            </p>
          ) : confirmedReservationId ? (
            <div className="active-reservation" role="status">
              <h4>Booking Confirmed</h4>
              <p>
                Your ticket has been confirmed and is available in My Bookings.
              </p>
              <code>{confirmedReservationId}</code>
            </div>
          ) : reservation ? (
            <div className="active-reservation">
              <div className="reservation-status">
                <div>
                  <h4>
                    <Clock3 size={18} /> Reservation Active
                  </h4>
                  <p>Your seats are temporarily held.</p>
                </div>
                <strong className="reservation-countdown">
                  {formatCountdown(remainingMs)}
                </strong>
              </div>

              <div className="reservation-details">
                <p>
                  <span>Reservation ID</span>
                  <code>{reservation.reservationId}</code>
                </p>
                <p>
                  <span>Reserved seats</span>
                  <strong>{reservation.showSeatIds.length}</strong>
                </p>
                <p>
                  <span>Total amount</span>
                  <strong>
                    {formatCurrency(Number(reservation.totalPrice))}
                  </strong>
                </p>
              </div>

              <p className="reservation-payment-note">
                Your seats are held temporarily. Use Razorpay Test Mode to
                complete payment. Your booking is confirmed only after the
                FlashTix backend verifies capture.
              </p>

              {isAuthenticated && accessToken && remainingMs > 0 && (
                <RazorpayCheckoutButton
                  key={reservation.reservationId}
                  reservationId={reservation.reservationId}
                  accessToken={accessToken}
                  expiresAt={reservation.expiresAt}
                  customerName={user?.fullName ?? ""}
                  customerEmail={user?.email ?? ""}
                  onConfirmed={handlePaymentConfirmed}
                />
              )}

              <button
                type="button"
                className="cancel-reservation-button"
                disabled={cancelling}
                onClick={cancelReservation}
              >
                <XCircle size={17} />
                {cancelling ? "Cancelling..." : "Cancel Reservation"}
              </button>
              <p className="booking-muted">
                Do not cancel or pay again if Razorpay has already reported a
                payment.
              </p>
            </div>
          ) : (
            <div className="booking-summary">
              <div className="booking-summary-info">
                <span>{selectedSeatIds.length} seat(s) selected</span>
                <strong>{formatCurrency(totalPrice)}</strong>
              </div>
              <button
                type="button"
                className="reserve-button"
                disabled={
                  submitting ||
                  restoring ||
                  loadingSeats ||
                  selectedSeatIds.length === 0
                }
                onClick={isAuthenticated ? reserveSeats : requireLogin}
              >
                {isAuthenticated ? (
                  <Ticket size={18} />
                ) : (
                  <LockKeyhole size={18} />
                )}
                {submitting
                  ? "Reserving..."
                  : isAuthenticated
                    ? "Reserve Seats"
                    : "Login to Reserve"}
              </button>
              <p className="booking-muted">
                Seats are held for five minutes after a successful reservation.
              </p>
            </div>
          )}
        </>
      )}
    </div>
  );
}
