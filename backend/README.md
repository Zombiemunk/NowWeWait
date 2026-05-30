# Now We Wait — Backend API Server

High-performance, lightweight Node.js + TypeScript + Hono REST backend for **Now We Wait** stop monitoring. Aggregates Bizkaibus spatial stops metadata, live arrivals, and service alerts in under 500ms (p95).

## Tech Stack
- **Runtime**: Node.js
- **Server**: Hono + `@hono/node-server`
- **Database**: SQLite (`better-sqlite3`) for high-speed indexing
- **Coordinate Conversion**: `proj4` for local UTM Zone 30N -> WGS84 translations
- **Parser**: `fast-xml-parser` for real-time Bizkaibus SOAP and SIRI feeds

---

## API Endpoints

### 1. GET `/nearby`
Acquires nearest stops and attaches live arrivals in **one round-trip**.
- **Query Parameters**:
  - `lat` (required): User's current latitude (e.g. `43.30917`)
  - `lon` (required): User's current longitude (e.g. `-2.46843`)
  - `n` (optional): Number of stops to return. Defaults to `3`.
- **Response Format**:
  ```json
  [
    {
      "id": "48018001",
      "name": "Erribera",
      "lat": 43.30917889,
      "lng": -2.46843131,
      "municipality": "BERRIATUA",
      "address": "en la plaza",
      "distance": 1,
      "lines": ["A3915", "A3916"],
      "arrivals": [
        {
          "line": "A3915",
          "route": "ONDARROA",
          "destination": "ONDARROA",
          "meters": 13748,
          "minutes": 20
        }
      ]
    }
  ]
  ```

### 2. GET `/stops`
Fuzzy autocomplete search by name, 8-digit exact stop code, or 4-digit sign code.
- **Query Parameters**:
  - `q` (required): Search keyword (minimum 2 chars)

### 3. GET `/stops/:id`
Retrieves single stop metadata and its serving lines.

### 4. GET `/stops/:id/arrivals`
Fetches upcoming real-time ETAs for all lines servicing a particular stop.

### 5. GET `/alerts?stop=:id`
Fetches active service alerts affecting a stop or the lines serving it.

---

## Local Setup

### 1. Install Dependencies
```bash
npm install
```

### 2. Run Database Ingestion
Fetches Bizkaia's 112+ municipalities' stops and indexes them locally:
```bash
npx tsx src/ingestion.ts
```

### 3. Run Development Server
```bash
npm run dev
```

### 4. Run Verification Tests
```bash
npx tsx src/test_endpoints.ts
```
