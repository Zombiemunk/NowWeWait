import http from 'http';

function requestJson(url: string): Promise<any> {
  return new Promise((resolve) => {
    http.get(url, (res) => {
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => {
        try {
          resolve(JSON.parse(data));
        } catch (e) {
          resolve({ error: 'Failed to parse JSON', raw: data });
        }
      });
    }).on('error', (err) => {
      resolve({ error: err.message });
    });
  });
}

async function run() {
  console.log("--------------------------------------------------");
  console.log("Verifying local Hono REST API endpoints...");
  console.log("--------------------------------------------------");

  // Test root
  console.log("\n1. Testing GET http://localhost:3000/");
  const root = await requestJson("http://localhost:3000/");
  console.log("Root Response:", root);

  // Test stops autocomplete
  console.log("\n2. Testing GET http://localhost:3000/stops?q=Erribera");
  const stops = await requestJson("http://localhost:3000/stops?q=Erribera");
  console.log("Search Results (first 2):", stops.slice(0, 2));

  // Test nearby endpoint with live SOAP arrivals fetch
  console.log("\n3. Testing GET http://localhost:3000/nearby?lat=43.30917&lon=-2.46843 (Berriatua coordinates)");
  console.time("Nearby Round Trip Time");
  const nearby = await requestJson("http://localhost:3000/nearby?lat=43.30917&lon=-2.46843");
  console.timeEnd("Nearby Round Trip Time");
  console.log("\nNearby Response Stop Count:", nearby.length);
  if (nearby.length > 0) {
    console.log("Closest Stop Details:", {
      id: nearby[0].id,
      name: nearby[0].name,
      distance: nearby[0].distance,
      municipality: nearby[0].municipality,
      lines: nearby[0].lines,
      arrivalsCount: nearby[0].arrivals.length
    });
    if (nearby[0].arrivals.length > 0) {
      console.log("First Arrival:", nearby[0].arrivals[0]);
    }
  }

  // Test alerts
  if (nearby.length > 0) {
    const stopId = nearby[0].id;
    console.log(`\n4. Testing GET http://localhost:3000/alerts?stop=${stopId}`);
    const alerts = await requestJson(`http://localhost:3000/alerts?stop=${stopId}`);
    console.log(`Alerts for stop ${stopId} (${nearby[0].name}):`, alerts);
  }
}

run();
