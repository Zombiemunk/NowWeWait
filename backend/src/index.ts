import { serve } from '@hono/node-server';
import { Hono } from 'hono';
import Database from 'better-sqlite3';
import { calculateHaversineDistance } from './utils/coordinates.js';
import { fetchSoapArrivals } from './utils/soap.js';
import { getAlertsForStop } from './utils/alerts.js';

const app = new Hono();

// Connect to read-only database
const DB_PATH = './stops.db';
const db = new Database(DB_PATH, { readonly: true });

// Precompile queries for high-speed performance
const getAllStopsQuery = db.prepare('SELECT id, name, lat, lng, municipality, address FROM stops');
const getStopLinesQuery = db.prepare('SELECT line_code, line_name FROM stop_lines WHERE stop_id = ?');
const getStopByIdQuery = db.prepare('SELECT id, name, lat, lng, municipality, address FROM stops WHERE id = ?');

// Fuzzy stops search (by name or 4-digit code suffix or exact ID)
const searchStopsQuery = db.prepare(`
  SELECT id, name, lat, lng, municipality, address 
  FROM stops 
  WHERE name LIKE ? 
     OR id LIKE ? 
     OR substr(id, -4) = ?
  LIMIT 20
`);

// Root landing info
app.get('/', (c) => {
  return c.json({
    name: 'Now We Wait API',
    version: '1.0.0',
    description: 'Bizkaibus High-Speed Native Arrivals Backend'
  });
});

/**
 * GET /nearby?lat=&lon=&n=3
 * 
 * Performance critical: fetches stops nearest to coordinates, then makes parallel SOAP
 * calls to fetch their current arrivals. Returns in a single combined round-trip payload.
 */
app.get('/nearby', async (c) => {
  const latStr = c.req.query('lat');
  const lonStr = c.req.query('lon');
  const nStr = c.req.query('n') || '3';

  if (!latStr || !lonStr) {
    return c.json({ error: 'Missing coordinates lat and lon' }, 400);
  }

  const userLat = parseFloat(latStr);
  const userLon = parseFloat(lonStr);
  const n = parseInt(nStr, 10);

  if (isNaN(userLat) || isNaN(userLon) || isNaN(n)) {
    return c.json({ error: 'Invalid parameter types' }, 400);
  }

  try {
    // 1. Fetch all stops from SQLite
    const stops = getAllStopsQuery.all() as any[];

    // 2. Perform fast in-memory Haversine distance scanning
    const stopsWithDistance = stops.map((stop) => {
      const distance = calculateHaversineDistance(userLat, userLon, stop.lat, stop.lng);
      return { ...stop, distance };
    });

    // 3. Sort by distance and pick the top N stops
    stopsWithDistance.sort((a, b) => a.distance - b.distance);
    const nearestStops = stopsWithDistance.slice(0, n);

    // 4. Concurrently fetch arrivals for all top stops in parallel
    const nearbyWithArrivals = await Promise.all(
      nearestStops.map(async (stop) => {
        const arrivals = await fetchSoapArrivals(stop.id);
        
        // Fetch servicing lines to attach to metadata
        const lines = getStopLinesQuery.all(stop.id) as { line_code: string; line_name: string }[];
        const lineCodes = Array.from(new Set(lines.map((l) => l.line_code)));

        return {
          id: stop.id,
          name: stop.name,
          lat: stop.lat,
          lng: stop.lng,
          municipality: stop.municipality,
          address: stop.address,
          distance: Math.round(stop.distance),
          lines: lineCodes,
          arrivals
        };
      })
    );

    return c.json(nearbyWithArrivals);
  } catch (error: any) {
    console.error('Error in /nearby endpoint:', error.message);
    return c.json({ error: 'Internal Server Error' }, 500);
  }
});

/**
 * GET /stops?q=<text>
 * 
 * Autocomplete stop search by name, exact stop code, or last 4 digits (e.g. parada code).
 */
app.get('/stops', async (c) => {
  const query = c.req.query('q');
  if (!query || query.trim().length < 2) {
    return c.json([]);
  }

  const cleanQuery = query.trim();
  const searchPattern = `%${cleanQuery}%`;

  try {
    const stops = searchStopsQuery.all(searchPattern, searchPattern, cleanQuery) as any[];

    const stopsWithLines = stops.map((stop) => {
      const lines = getStopLinesQuery.all(stop.id) as { line_code: string; line_name: string }[];
      const lineCodes = Array.from(new Set(lines.map((l) => l.line_code)));
      return {
        id: stop.id,
        name: stop.name,
        lat: stop.lat,
        lng: stop.lng,
        municipality: stop.municipality,
        address: stop.address,
        lines: lineCodes
      };
    });

    return c.json(stopsWithLines);
  } catch (error: any) {
    console.error('Error in /stops search:', error.message);
    return c.json({ error: 'Internal Server Error' }, 500);
  }
});

/**
 * GET /stops/:id
 * 
 * Stop metadata and serving lines.
 */
app.get('/stops/:id', async (c) => {
  const stopId = c.req.param('id');
  
  try {
    const stop = getStopByIdQuery.get(stopId) as any;
    if (!stop) {
      return c.json({ error: 'Stop not found' }, 404);
    }

    const lines = getStopLinesQuery.all(stopId) as { line_code: string; line_name: string }[];
    const uniqueLines = Array.from(
      new Map(lines.map((item) => [item.line_code, item])).values()
    );

    return c.json({
      id: stop.id,
      name: stop.name,
      lat: stop.lat,
      lng: stop.lng,
      municipality: stop.municipality,
      address: stop.address,
      lines: uniqueLines
    });
  } catch (error: any) {
    console.error('Error in /stops/:id endpoint:', error.message);
    return c.json({ error: 'Internal Server Error' }, 500);
  }
});

/**
 * GET /stops/:id/arrivals
 * 
 * Live arrivals for a single stop with quick SOAP query.
 */
app.get('/stops/:id/arrivals', async (c) => {
  const stopId = c.req.param('id');

  try {
    const stopExists = getStopByIdQuery.get(stopId) as any;
    if (!stopExists) {
      return c.json({ error: 'Stop not found' }, 404);
    }

    const arrivals = await fetchSoapArrivals(stopId);
    return c.json({
      stopId,
      arrivals
    });
  } catch (error: any) {
    console.error('Error in /stops/:id/arrivals endpoint:', error.message);
    return c.json({ error: 'Internal Server Error' }, 500);
  }
});

/**
 * GET /alerts?stop=<id>
 * 
 * Fetches active service alerts affecting a particular stop.
 */
app.get('/alerts', async (c) => {
  const stopId = c.req.query('stop');
  if (!stopId) {
    return c.json({ error: 'Missing stop query parameter' }, 400);
  }

  try {
    const stopExists = getStopByIdQuery.get(stopId) as any;
    if (!stopExists) {
      return c.json({ error: 'Stop not found' }, 404);
    }

    // Get lines serving this stop
    const lines = getStopLinesQuery.all(stopId) as { line_code: string; line_name: string }[];
    const lineCodes = Array.from(new Set(lines.map((l) => l.line_code)));

    const alerts = await getAlertsForStop(lineCodes);
    return c.json(alerts);
  } catch (error: any) {
    console.error('Error in /alerts endpoint:', error.message);
    return c.json({ error: 'Internal Server Error' }, 500);
  }
});

// Start the Hono Server
const port = 3000;
serve({
  fetch: app.fetch,
  port,
  hostname: '0.0.0.0'
}, (info) => {
  console.log(`Server is running on http://${info.address}:${info.port}`);
});
