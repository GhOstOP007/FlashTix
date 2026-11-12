# 🎟️ FlashTix

**FlashTix** is a full-stack event and movie ticket booking application built with **React, TypeScript, Spring Boot, and PostgreSQL**. It supports event discovery, showtime selection, interactive seat booking, temporary reservations, user accounts, and **Razorpay Test Mode** checkout.

> **Project status:** Active development / portfolio project. Payments are in **Razorpay Test Mode only**; this is **not production-ready**.

## ✨ Features

- 🔎 Browse and filter events by category, location, or name
- 🎬 View event details, upcoming showtimes, and venues
- 🪑 Interactive seat map with available, held, booked, and selected seat states
- ⏱️ Five-minute reservation holds with countdown and expiry handling
- 🔐 User registration and JWT-based authentication
- 🎫 My Bookings, reservation restoration, and cancellation
- 💳 Razorpay Test Mode Checkout with backend signature and payment verification
- ✅ Server-side booking fulfilment after verified payment capture
- 🔄 Payment reconciliation endpoints and groundwork for webhook recovery
- 🗃️ Database versioning with Flyway migrations

## 🖼️ Screenshots

Screenshots will appear here after actual application captures are added to `docs/screenshots/`. **These are placeholders, not screenshots of a running instance.**

<!--
Remove these HTML comments once each image has been added to the repository:

### Home & event discovery
![FlashTix home and events](docs/screenshots/home.png)

### Event details & show selection
![Event details and available shows](docs/screenshots/event-details.png)

### Interactive seat selection
![FlashTix seat selection](docs/screenshots/seat-selection.png)

### Reservation countdown
![Held reservation and countdown](docs/screenshots/reservation.png)

### Razorpay Test Mode Checkout
![Razorpay Test Mode payment checkout](docs/screenshots/razorpay-checkout.png)

### Confirmed booking
![Successful booking confirmation](docs/screenshots/booking-confirmed.png)

### My Bookings
![My Bookings screen](docs/screenshots/my-bookings.png)
-->

**Suggested captures:** home page, event details, seat map, active reservation, Razorpay *Test Mode* popup, confirmed booking, and My Bookings. Use PNG or WebP; blur emails, payment IDs, tokens, and any personal data before publishing.

## 🛠️ Tech stack

| Layer | Technology |
| --- | --- |
| Frontend | React, TypeScript, Vite, CSS, Lucide React |
| Backend | Java 21, Spring Boot, Spring Security |
| Authentication | JWT |
| Database | PostgreSQL |
| Migrations | Flyway |
| Payments | Razorpay Test Mode |
| Tooling | Maven Wrapper, Docker, Git |

## 📁 Project structure

```text
FlashTix/
├── frontend/
│   ├── src/
│   │   ├── components/
│   │   │   ├── EventBookingPanel.tsx
│   │   │   ├── RazorpayCheckoutButton.tsx
│   │   │   ├── MyBookings.tsx
│   │   │   └── AuthModal.tsx
│   │   ├── context/
│   │   │   └── AuthContext.tsx
│   │   └── App.tsx
│   └── package.json
├── backend/
│   ├── src/main/java/com/flashtix/
│   ├── src/main/resources/db/migration/
│   ├── mvnw.cmd
│   └── pom.xml
├── docs/
│   └── screenshots/   # Add your screenshots here
└── README.md
```

## 🚀 Getting started

### Prerequisites

- Java 21
- Node.js and npm
- PostgreSQL (local or Docker)
- Razorpay **Test Mode** API key ID and secret
- Git

### 1. Clone the repository

```powershell
git clone https://github.com/GhOstOP007/FlashTix.git
cd FlashTix
```

### 2. Configure the backend

Configure your PostgreSQL connection and JWT signing secret using the Spring Boot configuration/environment variable names defined in your local `backend/src/main/resources/` files.

For Razorpay Test Mode, configure these environment variables in the **same PowerShell session** that launches the backend:

```powershell
$env:JAVA_TOOL_OPTIONS = "-Duser.timezone=UTC"
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:RAZORPAY_ORDERS_ENABLED = "true"

$env:RAZORPAY_KEY_ID = Read-Host "Razorpay Test Key ID"
$secret = Read-Host "Razorpay Test Key Secret" -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try {
    $env:RAZORPAY_KEY_SECRET = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    Remove-Variable secret -ErrorAction SilentlyContinue
}
```

**Never commit passwords, JWT signing secrets, API key secrets, or private `.env` files.** The webhook secret is separate from the Razorpay API key secret.

Start the backend:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

The API normally runs at **http://localhost:8080**.

> **Note:** A local PostgreSQL database with the application's expected credentials and schema must be available. Flyway applies migrations on application startup when configured.

### 3. Start the frontend

Open another terminal from the repository root:

```powershell
cd frontend
npm install
npm run dev
```

Open **http://localhost:5173**.

### 4. Run tests and build

Backend:

```powershell
cd backend
$env:JAVA_TOOL_OPTIONS = "-Duser.timezone=UTC"
.\mvnw.cmd test
```

Frontend:

```powershell
cd frontend
npm run build
```

## 🎟️ Booking journey

1. Log in or register.
2. Select an event and showtime.
3. Choose up to six available seats.
4. Create a temporary reservation (five-minute hold).
5. Open Razorpay **Test Mode** Checkout.
6. The backend verifies payment evidence before booking seats.
7. View the confirmed reservation in My Bookings.

**Important:** A Checkout callback by itself does not constitute proof of successful payment. Payment verification and seat fulfilment are handled on the server.

## 🧩 API overview

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `POST` | `/api/auth/login` | Authenticate |
| `GET` | `/api/auth/me` | Get current user |
| `GET` | `/api/events` | Browse events |
| `GET` | `/api/shows?eventId={id}` | List event shows |
| `GET` | `/api/shows/{showId}/seats` | Seat availability |
| `POST` | `/api/reservations` | Hold seats |
| `GET` | `/api/reservations/{id}` | Reservation details |
| `POST` | `/api/reservations/{id}/cancel` | Cancel a hold |
| `POST` | `/api/dev/razorpay/orders` | Create Test Mode order |
| `POST` | `/api/dev/razorpay/orders/{id}/verify-checkout` | Verify Checkout and attempt fulfilment |
| `GET` | `/api/dev/razorpay/orders/{id}/reconcile` | Reconcile payment |

> The `/api/dev/...` endpoints are **development-only** and are not intended as a production payment interface.

## 🗺️ Roadmap

- [x] Event discovery and showtimes
- [x] Seat booking with five-minute holds
- [x] JWT authentication and booking history
- [x] Razorpay Test Mode Checkout
- [x] Server-verified payment fulfilment flow
- [ ] Automatic webhook-driven payment recovery
- [ ] Scheduled reconciliation and late-payment compensation
- [ ] QR-code tickets and venue check-in
- [ ] Admin dashboard and reporting
- [ ] Email confirmations
- [ ] Production hardening, security tests, and deployment

## 🔒 Security notes

FlashTix is under active development. Do **not** switch it to live payments without implementing and testing webhook delivery, recovery for uncertain payments, rate limiting, secret management, monitoring, and production security controls.

## 👨‍💻 Author

Built by [GhOstOP007](https://github.com/GhOstOP007).

---

If you find this project interesting, consider starring the repository ⭐
