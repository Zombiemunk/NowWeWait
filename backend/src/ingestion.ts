import http from 'http';
import Database from 'better-sqlite3';
import { XMLParser } from 'fast-xml-parser';
import { utmToLatLng, parseXmlCoordinate } from './utils/coordinates.js';

// Configuration
const DB_PATH = './stops.db';
const MAX_CONCURRENCY = 5;
const MUNICIPIO_LIMIT = 150; // Query 1 to 150 to cover all municipalities in Bizkaia

// Initialize DB
const db = new Database(DB_PATH);
db.pragma('journal_mode = WAL');

function setupDatabase() {
  console.log("Setting up SQLite database tables...");
  db.exec(`
    CREATE TABLE IF NOT EXISTS stops (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      lat REAL NOT NULL,
      lng REAL NOT NULL,
      municipality TEXT,
      address TEXT
    );

    CREATE TABLE IF NOT EXISTS stop_lines (
      stop_id TEXT,
      line_code TEXT,
      line_name TEXT,
      PRIMARY KEY (stop_id, line_code),
      FOREIGN KEY (stop_id) REFERENCES stops(id) ON DELETE CASCADE
    );

    CREATE INDEX IF NOT EXISTS idx_stops_coords ON stops(lat, lng);
    CREATE INDEX IF NOT EXISTS idx_stop_lines_stop ON stop_lines(stop_id);
  `);
}

// Helper to download a municipality's stops XML
function fetchMunicipioXml(code: number): Promise<string> {
  const url = `http://apps.bizkaia.eus/BBOA000M/rest/BBOA/GetParadasMunicipio?iCodigoMunicipio=${code}`;
  
  return new Promise((resolve, reject) => {
    http.get(url, {
      headers: { 'User-Agent': 'Mozilla/5.0' },
      timeout: 15000
    }, (res) => {
      if (res.statusCode !== 200) {
        reject(new Error(`HTTP Status ${res.statusCode}`));
        return;
      }
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => { resolve(data); });
    }).on('error', (err) => {
      reject(err);
    });
  });
}

// Ingestion Worker
async function ingestAll() {
  setupDatabase();

  const xmlParser = new XMLParser({
    ignoreAttributes: false,
    parseTagValue: false,
    isArray: (name) => ['GELTOKIA-PARADA', 'LINEA-LINEA'].includes(name)
  });

  const prepareStopInsert = db.prepare(`
    INSERT OR REPLACE INTO stops (id, name, lat, lng, municipality, address)
    VALUES (?, ?, ?, ?, ?, ?)
  `);

  const prepareLineInsert = db.prepare(`
    INSERT OR REPLACE INTO stop_lines (stop_id, line_code, line_name)
    VALUES (?, ?, ?)
  `);

  console.log(`Starting ingestion of stops from 1 to ${MUNICIPIO_LIMIT} municipalities...`);
  
  let stopsCount = 0;
  let linesCount = 0;

  // Process in batches to limit concurrency
  for (let i = 1; i <= MUNICIPIO_LIMIT; i += MAX_CONCURRENCY) {
    const promises: Promise<{ code: number; xml: string | null; error?: string }>[] = [];
    
    for (let j = 0; j < MAX_CONCURRENCY; j++) {
      const code = i + j;
      if (code > MUNICIPIO_LIMIT) break;
      
      promises.push(
        fetchMunicipioXml(code)
          .then((xml) => ({ code, xml }))
          .catch((err) => ({ code, xml: null, error: err.message }))
      );
    }

    const results = await Promise.all(promises);

    db.transaction(() => {
      for (const result of results) {
        if (result.error || !result.xml) {
          // Quietly skip empty or error responses since not all numbers are valid municipalities
          continue;
        }

        try {
          const parsed = xmlParser.parse(result.xml);
          const stopContainer = parsed['GELTOKIAK-PARADAS'];
          if (!stopContainer) continue;

          const municipalityName = stopContainer['UDALERRIA-MUNICIPIO'] || '';
          const stopsList = stopContainer['GELTOKIA-PARADA'] || [];

          if (stopsList.length > 0) {
            console.log(`Municipio ${result.code} (${municipalityName}): Found ${stopsList.length} stops`);
          }

          for (const stopNode of stopsList) {
            const stopId = stopNode['KODEA-CODIGO'];
            const stopName = stopNode['DESKRIPZIOA-DESCRIPCION'];
            const address = stopNode['HELBIDEA-DIRECCION'] || '';
            
            const geoloc = stopNode['GEOLOKALIZAZIOA-GEOLOCALIZACION'];
            if (!stopId || !stopName) continue;

            let lat = 0.0;
            let lng = 0.0;

            if (geoloc && geoloc['X'] && geoloc['Y']) {
              const x = parseXmlCoordinate(geoloc['X']);
              const y = parseXmlCoordinate(geoloc['Y']);
              if (x > 0 && y > 0) {
                const coords = utmToLatLng(x, y);
                lat = coords.lat;
                lng = coords.lng;
              }
            }

            // Insert stop metadata
            prepareStopInsert.run(stopId, stopName, lat, lng, municipalityName, address);
            stopsCount++;

            // Insert related lines
            const linesList = stopNode['LINEAK-LINEAS']?.['LINEA-LINEA'] || [];
            for (const lineNode of linesList) {
              const lineCode = lineNode['KODEA-CODIGO'];
              const lineName = lineNode['DESKRIPZIOA-DESCRIPCION'] || '';
              if (lineCode) {
                prepareLineInsert.run(stopId, lineCode, lineName);
                linesCount++;
              }
            }
          }
        } catch (e: any) {
          console.error(`Error parsing XML for Municipio ${result.code}:`, e.message);
        }
      }
    })();
  }

  console.log("-----------------------------------------");
  console.log(`Ingestion completed!`);
  console.log(`Total unique stops loaded: ${(db.prepare('SELECT count(*) as count FROM stops').get() as any).count}`);
  console.log(`Total stop-line associations loaded: ${(db.prepare('SELECT count(*) as count FROM stop_lines').get() as any).count}`);
  console.log("-----------------------------------------");
}

ingestAll().catch((err) => {
  console.error("Ingestion failed fatally:", err);
});
