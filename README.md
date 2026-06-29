# Order Management System

A standalone OMS: core order model with a schema-validated JSON extension
mechanism, a configurable workflow engine driving order lifecycle, and a
human task queue for manual workflow steps.

- [SPEC.md](SPEC.md) — backend design (data model, workflow engine, API surface)
- [UI-SPEC.md](UI-SPEC.md) — frontend design (Ops Console, Customer Portal, Admin Console)
- [GUIDE.md](GUIDE.md) — how to test end-to-end, extend order attributes, and configure workflow

## Prerequisites

- Java 21+
- Maven 3.9+
- Node.js 20+
- Docker (for local Postgres, and for the integration test suite)

## Quick start

Run these from the repo root, in order, each in its own terminal (or
background them as shown).

**1. Start Postgres**

```bash
docker compose up -d
```

This starts Postgres 16 on `localhost:5432` (db/user/password: `oms`/`oms`/`oms`,
see [docker-compose.yml](docker-compose.yml)).

**2. Start the backend**

```bash
mvn spring-boot:run
```

Runs on `http://localhost:8080`. Flyway applies all migrations on startup,
including a seeded `STANDARD` order type with a published workflow — there's
real data to look at immediately, no manual setup needed.

Swagger UI is at <http://localhost:8080/swagger-ui/index.html> (raw OpenAPI
spec at `/v3/api-docs`) — auto-generated from the controllers, includes
"Try it out" for every endpoint.

**3. Start the frontend**

```bash
cd web
npm install   # first time only
npm run dev
```

Runs on `http://localhost:5173`. The Vite dev server proxies `/orders`,
`/order-types`, `/tasks`, and `/workflow-definitions` to the backend on
`:8080`, so the browser sees everything as same-origin — no CORS config
needed.

**4. Open the app**

- Ops Console: <http://localhost:5173/ops/orders>
- Admin Console: <http://localhost:5173/admin/order-types>
- Customer Portal: `http://localhost:5173/track/{orderId}` (needs a real order ID — see below)

There's no order-creation UI in the Ops Console (orders are expected to come
from an upstream system hitting the API directly, not from manual ops entry —
see UI-SPEC.md §2.1). To get an order to look at:

```bash
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" -H "X-User-Id: dev" \
  -d '{"orderTypeCode":"STANDARD","customerRef":"cust-1","currency":"USD","totalAmount":"500.00","attributes":{"giftMessage":"hi"}}'
```

Copy the `orderId` from the response and use it in the Ops/Customer URLs above.

## Running the tests

```bash
mvn test     # unit tests only — no Docker needed
mvn verify   # unit + integration tests — needs Docker running (spins up Postgres via Testcontainers)
```

Frontend type-checking / build:

```bash
cd web
npx tsc -b   # type-check only
npm run build
```

There's no committed frontend test suite yet — UI changes have so far been
verified manually against the running stack in a browser.

## Stopping everything

```bash
docker compose down       # add -v to also wipe seeded/test data
# Ctrl-C the mvn spring-boot:run and npm run dev processes
```

## Project layout

```
src/main/java/com/oms/      Backend (Spring Boot)
src/main/resources/db/migration/   Flyway migrations
src/test/java/com/oms/      Backend tests (unit + Testcontainers integration)
web/                        Frontend (React + Vite)
SPEC.md                     Backend design spec
UI-SPEC.md                  Frontend design spec
```
