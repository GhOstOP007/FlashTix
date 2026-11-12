import { useEffect, useMemo, useState } from "react";

import {
  ArrowRight,
  CalendarDays,
  Clock3,
  LogIn,
  LogOut,
  MapPin,
  Search,
  Ticket,
  TicketCheck,
  UserRound,
  X,
} from "lucide-react";

import MyBookings from "./components/MyBookings";
import AuthModal from "./components/AuthModal";
import EventBookingPanel from "./components/EventBookingPanel";

import { useAuth } from "./context/AuthContext";

import "./App.css";

// =============================================
// TYPES
// =============================================

type Event = {
  id: number;
  title: string;
  description: string | null;
  category: string;
  startTime: string;
  venueName: string;
  city: string;
  createdAt: string;
};

// =============================================
// CONSTANTS
// =============================================

const categories = ["ALL", "CONCERT", "MOVIE", "SPORTS", "COMEDY", "THEATRE"];

// =============================================
// HELPERS
// =============================================

function formatDate(value: string): string {
  return new Intl.DateTimeFormat("en-IN", {
    day: "numeric",
    month: "short",
    year: "numeric",
    timeZone: "Asia/Kolkata",
  }).format(new Date(value));
}

function formatTime(value: string): string {
  return new Intl.DateTimeFormat("en-IN", {
    hour: "numeric",
    minute: "2-digit",
    hour12: true,
    timeZone: "Asia/Kolkata",
  }).format(new Date(value));
}

// =============================================
// MAIN APP
// =============================================

function App() {
  // -------------------------------------------
  // AUTHENTICATION
  // -------------------------------------------

  const { user, isAuthenticated, logout } = useAuth();

  const [authModalOpen, setAuthModalOpen] = useState(false);

  const [myBookingsOpen, setMyBookingsOpen] = useState(false);

  // -------------------------------------------
  // EVENT STATE
  // -------------------------------------------

  const [events, setEvents] = useState<Event[]>([]);
  const [loading, setLoading] = useState(true);

  const [error, setError] = useState<string | null>(null);

  const [selectedEvent, setSelectedEvent] = useState<Event | null>(null);

  // -------------------------------------------
  // SEARCH AND FILTERS
  // -------------------------------------------

  const [search, setSearch] = useState("");
  const [category, setCategory] = useState("ALL");

  // -------------------------------------------
  // FETCH EVENTS
  // -------------------------------------------

  useEffect(() => {
    const controller = new AbortController();

    async function loadEvents() {
      try {
        setLoading(true);

        const response = await fetch("/api/events", {
          signal: controller.signal,
        });

        if (!response.ok) {
          throw new Error(`Unable to load events: HTTP ${response.status}`);
        }

        const data = (await response.json()) as Event[];

        if (!Array.isArray(data)) {
          throw new Error("Unexpected event response from the server.");
        }

        setEvents(data);
        setError(null);
      } catch (err) {
        if (controller.signal.aborted) {
          return;
        }

        setError(err instanceof Error ? err.message : "Something went wrong.");
      } finally {
        if (!controller.signal.aborted) {
          setLoading(false);
        }
      }
    }

    void loadEvents();

    return () => {
      controller.abort();
    };
  }, []);

  // -------------------------------------------
  // FILTER EVENTS
  // -------------------------------------------

  const filteredEvents = useMemo(() => {
    const query = search.trim().toLowerCase();

    return events.filter((event) => {
      const matchesCategory =
        category === "ALL" || event.category.toUpperCase() === category;

      const searchableText = [event.title, event.city, event.venueName]
        .join(" ")
        .toLowerCase();

      return matchesCategory && searchableText.includes(query);
    });
  }, [events, search, category]);

  // -------------------------------------------
  // MODAL FUNCTIONS
  // -------------------------------------------

  function openEvent(event: Event) {
    setSelectedEvent(event);
  }

  function closeEvent() {
    setSelectedEvent(null);
  }

  function openLogin() {
    setAuthModalOpen(true);
  }

  function closeLogin() {
    setAuthModalOpen(false);
  }

  function handleLogout() {
    setMyBookingsOpen(false);
    setAuthModalOpen(false);
    setSelectedEvent(null);
    logout();
  }

  // -------------------------------------------
  // RENDER
  // -------------------------------------------

  return (
    <div className="app">
      {/* ===================================== */}
      {/* NAVBAR */}
      {/* ===================================== */}

      <header className="navbar">
        <div className="nav-container">
          <a href="/" className="logo">
            <Ticket size={28} />
            <span>FlashTix</span>
          </a>

          <span className="nav-tagline">Your next experience starts here.</span>

          <div className="nav-auth-actions">
            {isAuthenticated && user ? (
              <>
                <span className="nav-user-name">
                  <UserRound size={17} />
                  {user.fullName}
                </span>

                <button
                  type="button"
                  className="nav-auth-button bookings"
                  onClick={() => {
                    setSelectedEvent(null);
                    setMyBookingsOpen(true);
                  }}
                >
                  <TicketCheck size={17} />
                  My Bookings
                </button>

                <button
                  type="button"
                  className="nav-auth-button logout"
                  onClick={handleLogout}
                >
                  <LogOut size={17} />
                  Logout
                </button>
              </>
            ) : (
              <button
                type="button"
                className="nav-auth-button"
                onClick={openLogin}
              >
                <LogIn size={17} />
                Login / Register
              </button>
            )}
          </div>
        </div>
      </header>

      {/* ===================================== */}
      {/* MAIN CONTENT */}
      {/* ===================================== */}

      <main>
        {/* HERO SECTION */}

        <section className="hero">
          <div className="hero-content">
            <span className="eyebrow">
              LIVE EXPERIENCES, UNFORGETTABLE MOMENTS
            </span>

            <h1>
              Don't just watch.
              <br />
              <span>Be there.</span>
            </h1>

            <p>
              Discover concerts, movies, sports, comedy and more. Find your next
              unforgettable experience.
            </p>

            <a href="#events" className="hero-button">
              Explore Events
              <ArrowRight size={18} />
            </a>
          </div>

          <div className="hero-decoration" aria-hidden="true">
            <Ticket size={150} strokeWidth={0.7} />
          </div>
        </section>

        {/* ================================= */}
        {/* EVENTS SECTION */}
        {/* ================================= */}

        <section id="events" className="events-section">
          <div className="section-header">
            <div>
              <span className="section-label">EXPLORE WHAT'S HAPPENING</span>

              <h2>Upcoming Events</h2>

              <p>Find something worth looking forward to.</p>
            </div>

            <span className="event-count">{filteredEvents.length} events</span>
          </div>

          {/* SEARCH AND FILTER BAR */}

          <div className="filter-bar">
            <div className="search-box">
              <Search size={19} />

              <input
                type="search"
                placeholder="Search events, cities, venues..."
                value={search}
                onChange={(e) => {
                  setSearch(e.target.value);
                }}
                aria-label="Search events"
              />
            </div>

            <div className="category-filters">
              {categories.map((item) => (
                <button
                  key={item}
                  type="button"
                  className={category === item ? "filter active" : "filter"}
                  onClick={() => {
                    setCategory(item);
                  }}
                  aria-pressed={category === item}
                >
                  {item === "ALL" ? "All Events" : item}
                </button>
              ))}
            </div>
          </div>

          {/* LOADING STATE */}

          {loading && (
            <div className="status-box" role="status">
              Loading amazing experiences...
            </div>
          )}

          {/* ERROR STATE */}

          {error && (
            <div className="status-box error" role="alert">
              <h3>Unable to fetch events</h3>

              <p>{error}</p>

              <p>Make sure Spring Boot is running.</p>
            </div>
          )}

          {/* EMPTY STATE */}

          {!loading && !error && filteredEvents.length === 0 && (
            <div className="status-box">
              <Ticket size={36} />

              <h3>No events found</h3>

              <p>Try another category or search term.</p>
            </div>
          )}

          {/* EVENT GRID */}

          {!loading && !error && (
            <div className="events-grid">
              {filteredEvents.map((event) => (
                <article className="event-card" key={event.id}>
                  <div className={`event-art ` + event.category.toLowerCase()}>
                    <span className="category-label">{event.category}</span>

                    <Ticket size={72} strokeWidth={0.8} className="art-icon" />
                  </div>

                  <div className="event-body">
                    <div className="event-date">
                      <CalendarDays size={15} />
                      {formatDate(event.startTime)}
                    </div>

                    <h3>{event.title}</h3>

                    <div className="event-location">
                      <MapPin size={15} />

                      <span>
                        {event.venueName}, {event.city}
                      </span>
                    </div>

                    <button
                      type="button"
                      className="details-button"
                      onClick={() => {
                        openEvent(event);
                      }}
                    >
                      View Details
                      <ArrowRight size={16} />
                    </button>
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      </main>

      {/* ===================================== */}
      {/* FOOTER */}
      {/* ===================================== */}

      <footer className="footer">
        <span>FlashTix © {new Date().getFullYear()}</span>

        <span>Built with React + Spring Boot</span>
      </footer>

      {/* ===================================== */}
      {/* EVENT DETAILS MODAL */}
      {/* ===================================== */}

      {selectedEvent && (
        <div className="modal-backdrop" onClick={closeEvent}>
          <section
            className="event-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="event-modal-title"
            onClick={(e) => {
              e.stopPropagation();
            }}
          >
            {/* CLOSE BUTTON */}

            <button
              className="close-button"
              type="button"
              aria-label="Close event details"
              onClick={closeEvent}
            >
              <X size={22} />
            </button>

            {/* EVENT INFORMATION */}

            <span className="section-label">{selectedEvent.category}</span>

            <h2 id="event-modal-title">{selectedEvent.title}</h2>

            <p className="modal-description">
              {selectedEvent.description ||
                "Discover this upcoming experience."}
            </p>

            <div className="modal-info">
              <p>
                <CalendarDays size={18} />
                {formatDate(selectedEvent.startTime)}
              </p>

              <p>
                <Clock3 size={18} />
                {formatTime(selectedEvent.startTime)} IST
              </p>

              <p>
                <MapPin size={18} />
                {selectedEvent.venueName}, {selectedEvent.city}
              </p>
            </div>

            {/* SEAT BOOKING AND CHECKOUT */}

            <EventBookingPanel
              key={selectedEvent.id}
              eventId={selectedEvent.id}
              onRequireAuth={openLogin}
            />
          </section>
        </div>
      )}

      {/* ===================================== */}
      {/* AUTH MODAL */}
      {/* ===================================== */}

      {authModalOpen && <AuthModal onClose={closeLogin} />}

      {/* ===================================== */}
      {/* MY BOOKINGS MODAL */}
      {/* ===================================== */}

      {myBookingsOpen && isAuthenticated && (
        <MyBookings
          onClose={() => {
            setMyBookingsOpen(false);
          }}
        />
      )}
    </div>
  );
}

export default App;
