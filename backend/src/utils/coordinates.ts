import proj4 from 'proj4';

// Define ETRS89 / UTM Zone 30N (EPSG:25830) and WGS84 (EPSG:4326)
proj4.defs("EPSG:25830", "+proj=utm +zone=30 +ellps=GRS80 +units=m +no_defs");
proj4.defs("EPSG:4326", "+proj=longlat +datum=WGS84 +no_defs");

/**
 * Converts UTM Zone 30N (X, Y in meters) to WGS84 Latitude and Longitude.
 * Bizkaia stop coordinates are stored as UTM Zone 30N.
 */
export function utmToLatLng(x: number, y: number): { lat: number; lng: number } {
  // proj4 takes coordinates as [Easting (X), Northing (Y)]
  // and returns [Longitude (Lng), Latitude (Lat)]
  const [lng, lat] = proj4("EPSG:25830", "EPSG:4326", [x, y]);
  return { lat, lng };
}

/**
 * Parses a coordinate string from Bizkaibus XML (which uses commas for decimals, e.g. "543109,4500")
 * into a valid number.
 */
export function parseXmlCoordinate(coordStr: string): number {
  if (!coordStr) return 0;
  const cleanStr = coordStr.replace(',', '.').trim();
  return parseFloat(cleanStr);
}

/**
 * Calculates the distance between two GPS coordinates using the Haversine formula.
 * Returns distance in meters.
 */
export function calculateHaversineDistance(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number
): number {
  const R = 6371e3; // Earth radius in meters
  const phi1 = (lat1 * Math.PI) / 180;
  const phi2 = (lat2 * Math.PI) / 180;
  const deltaPhi = ((lat2 - lat1) * Math.PI) / 180;
  const deltaLambda = ((lon2 - lon1) * Math.PI) / 180;

  const a =
    Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) +
    Math.cos(phi1) *
      Math.cos(phi2) *
      Math.sin(deltaLambda / 2) *
      Math.sin(deltaLambda / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

  return R * c; // in meters
}
