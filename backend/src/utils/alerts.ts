import https from 'https';
import { XMLParser } from 'fast-xml-parser';

export interface Alert {
  id: string;
  summaryEs: string;
  summaryEu: string;
  descriptionEs: string;
  descriptionEu: string;
  affectedLines: string[];
  startTime?: string;
  endTime?: string;
}

let cachedAlerts: Alert[] = [];
let cacheTimestamp = 0;
const CACHE_DURATION_MS = 60 * 1000; // Cache for 60 seconds

function fetchAlertsXml(): Promise<string> {
  const url = 'https://ctb-siri.s3.eu-south-2.amazonaws.com/bizkaibus-service-alerts.xml';
  return new Promise((resolve) => {
    https.get(url, {
      headers: { 'User-Agent': 'Mozilla/5.0' },
      timeout: 10000
    }, (res) => {
      if (res.statusCode !== 200) {
        resolve('');
        return;
      }
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => { resolve(data); });
    }).on('error', (err) => {
      console.error('Failed to fetch service alerts:', err.message);
      resolve('');
    });
  });
}

/**
 * Fetches and parses all active service alerts. Uses an in-memory 60s cache.
 */
export async function getActiveAlerts(): Promise<Alert[]> {
  const now = Date.now();
  if (now - cacheTimestamp < CACHE_DURATION_MS && cachedAlerts.length > 0) {
    return cachedAlerts;
  }

  const xml = await fetchAlertsXml();
  if (!xml) {
    return cachedAlerts; // Return cached if download fails
  }

  try {
    const parser = new XMLParser({
      ignoreAttributes: false,
      parseTagValue: false,
      isArray: (name) => [
        'PtSituationElement',
        'AffectedVehicleJourney',
        'Summary',
        'Description'
      ].includes(name)
    });

    const parsed = parser.parse(xml);
    const situationsList = parsed['Siri']?.['ServiceDelivery']?.['SituationExchangeDelivery']?.['Situations']?.['PtSituationElement'] || [];

    const alerts: Alert[] = [];
    let idCounter = 1;

    for (const sit of situationsList) {
      const validity = sit['ValidityPeriod'] || {};
      const startTime = validity['StartTime'];
      const endTime = validity['EndTime'];

      // Summary nodes
      const summaries = sit['Summary'] || [];
      let summaryEs = '';
      let summaryEu = '';
      for (const sum of summaries) {
        const lang = sum['@_xml:lang'] || '';
        if (lang === 'es') summaryEs = sum['#text'] || sum;
        else if (lang === 'eu') summaryEu = sum['#text'] || sum;
      }

      // Fallbacks if only single summary or no lang attribute
      if (!summaryEs && summaries.length > 0) {
        summaryEs = summaries[0]['#text'] || summaries[0];
      }

      // Description nodes
      const descriptions = sit['Description'] || [];
      let descriptionEs = '';
      let descriptionEu = '';
      for (const desc of descriptions) {
        const lang = desc['@_xml:lang'] || '';
        if (lang === 'es') descriptionEs = desc['#text'] || desc;
        else if (lang === 'eu') descriptionEu = desc['#text'] || desc;
      }

      if (!descriptionEs && descriptions.length > 0) {
        descriptionEs = descriptions[0]['#text'] || descriptions[0];
      }

      // Affected Lines
      const affectedLines: string[] = [];
      const affectedJourneys = sit['Affects']?.['VehicleJourneys']?.['AffectedVehicleJourney'] || [];
      for (const journey of affectedJourneys) {
        const lineRef = journey['LineRef'];
        if (lineRef) {
          // Normalize line ref by stripping any non-alphanumeric chars
          affectedLines.push(String(lineRef).trim());
        }
      }

      alerts.push({
        id: `alert-${idCounter++}`,
        summaryEs,
        summaryEu,
        descriptionEs: descriptionEs || summaryEs,
        descriptionEu: descriptionEu || summaryEu,
        affectedLines,
        startTime,
        endTime
      });
    }

    cachedAlerts = alerts;
    cacheTimestamp = now;
    return alerts;
  } catch (e: any) {
    console.error('Error parsing service alerts XML:', e.message);
    return cachedAlerts;
  }
}

/**
 * Retrieves alerts that affect a specific stop or its lines.
 */
export async function getAlertsForStop(stopLines: string[]): Promise<Alert[]> {
  const alerts = await getActiveAlerts();
  if (stopLines.length === 0) return [];

  // Standardize stop line codes, e.g. "A3915" -> ["A3915", "3915"]
  const normalizedStopLines = new Set<string>();
  for (const line of stopLines) {
    normalizedStopLines.add(line);
    // Add raw numeric part if it starts with letter (e.g. A3915 -> 3915)
    if (/^[A-Za-z]\d+$/.test(line)) {
      normalizedStopLines.add(line.substring(1));
    }
  }

  return alerts.filter((alert) => {
    // Check if there is an intersection between alert lines and stop lines
    return alert.affectedLines.some((alertLine) => {
      // Direct match or numeric part matches
      if (normalizedStopLines.has(alertLine)) return true;
      const strippedAlert = alertLine.replace(/^[A-Za-z]/, '');
      return normalizedStopLines.has(strippedAlert);
    });
  });
}
