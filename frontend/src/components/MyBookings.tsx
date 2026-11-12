import { useCallback, useEffect, useMemo, useState } from "react";

import {
  CalendarDays,
  Clock3,
  MapPin,
  RefreshCw,
  Ticket,
  X,
  XCircle,
} from "lucide-react";

import { useAuth } from "../context/AuthContext";
import "./MyBookings.css";

type BookingStatus = "HELD" | "CONFIRMED" | "CANCELLED" | "EXPIRED";

type BookingFilter = "ALL" | "HELD" | "CONFIRMED" | "CANCELLED" | "EXPIRED";

interface Reservation {
  reservationId: string;
  showId: number;
  showSeatIds: number[];
  status: string;
  totalPrice: number;
  expiresAt: string;
  createdAt: string;
}

interface ShowDetails {
  id: number;
  eventId: number;
  venueId: number;
  startTime: string;
  status: string;
}

interface EventDetails {
  id: number;
  title: string;
  venueName: string;
  city: string;
}

interface SeatDetails {
  id: number;
  rowLabel: string;
  seatNumber: number;
}

interface BookingInfo {
  eventTitle: string;
  venue: string;
  showTime: string | null;
  seatLabels: Record<number, string>;
}

interface MyBookingsProps {
  onClose: () => void;
}

const FILTERS: BookingFilter[] = [
  "ALL",
  "HELD",
  "CONFIRMED",
  "CANCELLED",
  "EXPIRED",
];

function formatPrice(price: number) {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 2,
  }).format(price);
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat("en-IN", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "Asia/Kolkata",
  }).format(new Date(value));
}

function formatCountdown(milliseconds: number) {
  const seconds = Math.max(0, Math.ceil(milliseconds / 1000));

  const minutes = Math.floor(seconds / 60);
  const remainder = seconds % 60;

  return `${String(minutes).padStart(2, "0")}:${String(remainder).padStart(
    2,
    "0",
  )}`;
}

function effectiveStatus(reservation: Reservation, now: number): BookingStatus {
  if (
    reservation.status === "HELD" &&
    Date.parse(reservation.expiresAt) <= now
  ) {
    return "EXPIRED";
  }

  return reservation.status as BookingStatus;
}

async function getError(response: Response) {
  try {
    const body = await response.json();

    if (typeof body.detail === "string") {
      return body.detail;
    }

    if (typeof body.message === "string") {
      return body.message;
    }
  } catch {
    // Use HTTP status if the body is unavailable.
  }

  return `Request failed (HTTP ${response.status})`;
}

export default function MyBookings({ onClose }: MyBookingsProps) {
  const { user, accessToken, isAuthenticated, logout } = useAuth();

  const [bookings, setBookings] = useState<Reservation[]>([]);

  const [bookingInfo, setBookingInfo] = useState<Record<number, BookingInfo>>(
    {},
  );

  const [filter, setFilter] = useState<BookingFilter>("ALL");

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  const [cancellingId, setCancellingId] = useState<string | null>(null);

  const [error, setError] = useState<string | null>(null);

  const [message, setMessage] = useState<string | null>(null);

  const [now, setNow] = useState(Date.now());

  // ---------------------------------------------
  // 1. Fetch reservation history and event info
  // ---------------------------------------------

  const loadBookings = useCallback(
    async (signal?: AbortSignal) => {
      if (!accessToken) return;

      setRefreshing(true);
      setError(null);

      try {
        const response = await fetch("/api/reservations", {
          headers: {
            Authorization: `Bearer ${accessToken}`,
          },
          signal,
        });

        if (response.status === 401) {
          logout();
          throw new Error("Your session has expired. Please log in again.");
        }

        if (!response.ok) {
          throw new Error(await getError(response));
        }

        const data: Reservation[] = await response.json();

        if (signal?.aborted) return;

        setBookings(
          [...data].sort(
            (a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt),
          ),
        );

        // Resolve each show's details only once.
        const showIds = [...new Set(data.map((booking) => booking.showId))];

        const details = await Promise.all(
          showIds.map(async (showId) => {
            const fallback: BookingInfo = {
              eventTitle: `Show #${showId}`,
              venue: "Venue details unavailable",
              showTime: null,
              seatLabels: {},
            };

            try {
              const [showResponse, seatsResponse] = await Promise.all([
                fetch(`/api/shows/${showId}`, {
                  signal,
                }),
                fetch(`/api/shows/${showId}/seats`, {
                  signal,
                }),
              ]);

              if (!showResponse.ok) {
                return [showId, fallback] as const;
              }

              const show: ShowDetails = await showResponse.json();

              const info: BookingInfo = {
                ...fallback,
                showTime: show.startTime,
              };

              if (seatsResponse.ok) {
                const seats: SeatDetails[] = await seatsResponse.json();

                info.seatLabels = Object.fromEntries(
                  seats.map((seat) => [
                    seat.id,
                    `${seat.rowLabel}${seat.seatNumber}`,
                  ]),
                );
              }

              const eventResponse = await fetch(`/api/events/${show.eventId}`, {
                signal,
              });

              if (eventResponse.ok) {
                const event: EventDetails = await eventResponse.json();

                info.eventTitle = event.title;
                info.venue = `${event.venueName}, ${event.city}`;
              }

              return [showId, info] as const;
            } catch {
              return [showId, fallback] as const;
            }
          }),
        );

        if (signal?.aborted) return;

        setBookingInfo(Object.fromEntries(details));
      } catch (err) {
        if (signal?.aborted) return;

        setError(
          err instanceof Error ? err.message : "Unable to load bookings.",
        );
      } finally {
        if (!signal?.aborted) {
          setLoading(false);
          setRefreshing(false);
        }
      }
    },
    [accessToken, logout],
  );

  // ---------------------------------------------
  // 2. Load on mount / authentication change
  // ---------------------------------------------

  useEffect(() => {
    const controller = new AbortController();

    if (isAuthenticated && accessToken) {
      void loadBookings(controller.signal);
    } else {
      setBookings([]);
      setBookingInfo({});
      setLoading(false);
    }

    return () => controller.abort();
  }, [isAuthenticated, accessToken, loadBookings]);

  // ---------------------------------------------
  // 3. Update countdowns
  // ---------------------------------------------

  useEffect(() => {
    const interval = window.setInterval(() => {
      setNow(Date.now());
    }, 1000);

    return () => window.clearInterval(interval);
  }, []);

  // Close with Escape
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        onClose();
      }
    }

    window.addEventListener("keydown", onKeyDown);

    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  // ---------------------------------------------
  // 4. Filter reservations
  // ---------------------------------------------

  const filteredBookings = useMemo(() => {
    return bookings.filter((booking) => {
      if (filter === "ALL") return true;

      return effectiveStatus(booking, now) === filter;
    });
  }, [bookings, filter, now]);

  const activeCount = useMemo(
    () =>
      bookings.filter((booking) => effectiveStatus(booking, now) === "HELD")
        .length,
    [bookings, now],
  );

  // ---------------------------------------------
  // 5. Cancel an active reservation
  // ---------------------------------------------

  async function cancelBooking(reservationId: string) {
    if (!accessToken || cancellingId) return;

    const confirmed = window.confirm(
      "Cancel this reservation and release its seats?",
    );

    if (!confirmed) return;

    setCancellingId(reservationId);
    setError(null);
    setMessage(null);

    try {
      const response = await fetch(
        `/api/reservations/${reservationId}/cancel`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${accessToken}`,
          },
        },
      );

      if (response.status === 401) {
        logout();
        throw new Error("Your session has expired. Please log in again.");
      }

      if (!response.ok) {
        throw new Error(await getError(response));
      }

      const updated: Reservation = await response.json();

      setBookings((previous) =>
        previous.map((booking) =>
          booking.reservationId === updated.reservationId ? updated : booking,
        ),
      );

      setMessage("Reservation cancelled. Your seats have been released.");

      // Keep the database as the source of truth.
      await loadBookings();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Cancellation failed.");
    } finally {
      setCancellingId(null);
    }
  }

  // ---------------------------------------------
  // 6. Render
  // ---------------------------------------------

  return (
    <div
      className="my-bookings-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <section
        className="my-bookings-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="my-bookings-title"
      >
        <header className="my-bookings-header">
          <div>
            <span className="my-bookings-eyebrow">YOUR EXPERIENCES</span>

            <h2 id="my-bookings-title">My Bookings</h2>

            <p>
              {user?.fullName
                ? `Welcome, ${user.fullName}`
                : "Manage your reservations"}
            </p>
          </div>

          <button
            type="button"
            className="my-bookings-close"
            onClick={onClose}
            aria-label="Close bookings"
          >
            <X size={21} />
          </button>
        </header>

        <div className="my-bookings-toolbar">
          <div className="my-bookings-summary">
            <span>
              <Ticket size={16} />
              {bookings.length} total
            </span>

            <span>
              <Clock3 size={16} />
              {activeCount} active
            </span>
          </div>

          <button
            type="button"
            className="my-bookings-refresh"
            disabled={refreshing}
            onClick={() => void loadBookings()}
          >
            <RefreshCw size={16} />
            Refresh
          </button>
        </div>

        <div className="my-bookings-filters">
          {FILTERS.map((item) => (
            <button
              type="button"
              key={item}
              className={filter === item ? "active" : ""}
              onClick={() => setFilter(item)}
            >
              {item === "ALL"
                ? "All"
                : item.charAt(0) + item.slice(1).toLowerCase()}
            </button>
          ))}
        </div>

        {error && (
          <div className="my-bookings-error" role="alert">
            {error}
          </div>
        )}

        {message && (
          <div className="my-bookings-message" role="status">
            {message}
          </div>
        )}

        {loading ? (
          <div className="my-bookings-empty">Loading your reservations...</div>
        ) : filteredBookings.length === 0 ? (
          <div className="my-bookings-empty">
            <Ticket size={38} />
            <h3>No bookings found</h3>
            <p>
              {filter === "ALL"
                ? "Your future experiences will appear here."
                : "No reservations match this filter."}
            </p>
          </div>
        ) : (
          <div className="my-bookings-list">
            {filteredBookings.map((booking) => {
              const status = effectiveStatus(booking, now);

              const info = bookingInfo[booking.showId];

              const remainingMs = Date.parse(booking.expiresAt) - now;

              const seatLabels = booking.showSeatIds.map(
                (id) => info?.seatLabels[id] ?? `Seat #${id}`,
              );

              return (
                <article
                  className="my-booking-card"
                  key={booking.reservationId}
                >
                  <div className="my-booking-card-heading">
                    <div>
                      <span className="my-booking-category">
                        EVENT RESERVATION
                      </span>

                      <h3>{info?.eventTitle ?? `Show #${booking.showId}`}</h3>
                    </div>

                    <span
                      className={`my-booking-status ${status.toLowerCase()}`}
                    >
                      {status}
                    </span>
                  </div>

                  <div className="my-booking-meta">
                    {info?.showTime && (
                      <span>
                        <CalendarDays size={15} />
                        {formatDate(info.showTime)}
                      </span>
                    )}

                    {info?.venue && (
                      <span>
                        <MapPin size={15} />
                        {info.venue}
                      </span>
                    )}
                  </div>

                  <div className="my-booking-seat-list">
                    {seatLabels.map((label, i) => (
                      <span key={`${label}-${i}`}>{label}</span>
                    ))}
                  </div>

                  <div className="my-booking-amount">
                    <span>{booking.showSeatIds.length} seat(s)</span>

                    <strong>{formatPrice(Number(booking.totalPrice))}</strong>
                  </div>

                  {status === "HELD" && (
                    <div className="my-booking-timer">
                      <Clock3 size={17} />
                      <span>Hold expires in</span>
                      <strong>{formatCountdown(remainingMs)}</strong>
                    </div>
                  )}

                  <div className="my-booking-footer">
                    <div className="my-booking-reference">
                      <span>Reservation ID</span>

                      <code>{booking.reservationId}</code>
                    </div>

                    {status === "HELD" && (
                      <button
                        type="button"
                        className="my-booking-cancel"
                        disabled={cancellingId !== null}
                        onClick={() =>
                          void cancelBooking(booking.reservationId)
                        }
                      >
                        <XCircle size={16} />

                        {cancellingId === booking.reservationId
                          ? "Cancelling..."
                          : "Cancel"}
                      </button>
                    )}
                  </div>

                  {status === "HELD" && (
                    <p className="my-booking-note">
                      This is a temporary hold, not a confirmed ticket. Payment
                      integration is coming later.
                    </p>
                  )}
                </article>
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
}
