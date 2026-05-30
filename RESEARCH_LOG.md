# Now We Wait — Developer Research Log

> **Project:** Now We Wait — GPS-based Bizkaibus arrival app
> **Stack:** Kotlin + Jetpack Compose (Android), TypeScript + Hono (backend), SQLite, Railway
> **Started:** May 2026

Questions, dead ends, and conclusions behind the decisions in this codebase. Not a spec. Not marketing.

---

## 1. Finding the Bizkaibus API

**Problem:** The QR codes on bus stops redirect to a web page showing live arrivals. Was it hitting a real API underneath, or scraping rendered HTML?

**Where I looked:**

- [bizkaia.eus/apps/danok/tqws/](http://apli.bizkaia.net/apps/danok/tqws/tq.asmx) — Found the SOAP endpoint in DevTools. The QR redirect loads an `.aspx` page that calls this `.asmx` endpoint in the background. XHR going to `apli.bizkaia.net/apps/danok/tqws/tq.asmx`. That was the key.
- [apps.bizkaia.eus/BBOA000M/rest/BBOA/](http://apps.bizkaia.eus/BBOA000M/rest/BBOA/GetParadasMunicipio?iCodigoMunicipio=1) — Separate REST endpoint for stop metadata. Found it by guessing URL patterns after `BBOA` appeared in a network request. No API key, no auth, just a municipality code.
- Stack Overflow — searched "bizkaibus api endpoint" and got nothing. Basque Country transit APIs are not a popular topic on SO.

**Conclusion:**
Two separate APIs: REST for stop static data (names, coordinates, lines), SOAP for live arrivals. The REST one is straightforward — pass `iCodigoMunicipio=1` through `150` and iterate. The SOAP one needs a proper envelope and a `SOAPAction` header. `GetParadasMunicipio` returns coordinates in UTM Zone 30N, not lat/lng.

**Key pattern:**
```
GET http://apps.bizkaia.eus/BBOA000M/rest/BBOA/GetParadasMunicipio?iCodigoMunicipio=48
POST http://apli.bizkaia.net/apps/danok/tqws/tq.asmx
SOAPAction: "http://tempuri.org/LANTIK_TQWS/TQ/GetPasoParada"
```
Both are HTTP only. On Android this means `usesCleartextTraffic` — but since Railway proxies the SOAP call, only the backend makes that cleartext request.

---

## 2. What the SOAP Response Actually Contains

**Problem:** What does `GetPasoParada` actually return? The WSDL exists but says the return type is `string`. That string is itself XML, escaped with HTML entities. A layer cake.

**Where I looked:**

- [apli.bizkaia.net/apps/danok/tqws/tq.asmx?WSDL](http://apli.bizkaia.net/apps/danok/tqws/tq.asmx?WSDL) — `GetPasoParadaResult` is typed `xsd:string`. That string is XML escaped with HTML entities (`&lt;`, `&gt;`). Spent about an hour treating the outer response as structured XML before realising the inner content needed unescaping first.
- Manual `curl` POST with a hardcoded stop code — this explained the structure. The result starts with `1®` before the inner XML, a status code prefix that needs stripping.

**Conclusion:**
Parse flow: outer SOAP envelope → extract `GetPasoParadaResult` text node → HTML-unescape → strip the `1®` prefix → parse the inner XML as a fresh document. Each `PasoParada` node has `e1` and `e2` child nodes for the next two arrivals, each with `destino`, `metros`, and `minutos`.

---

## 3. Parsing XML in TypeScript — Which Library

**Problem:** Needed XML parsing in Node.js. Both `node-soap` and `xml2js` have a reputation for being painful.

**Where I looked:**

- [npmjs.com/package/node-soap](https://www.npmjs.com/package/node-soap) — The auto-generated client from the WSDL worked in theory. The double-escaped response confused it and returned the inner XML as a raw string anyway. Abandoned.
- [npmjs.com/package/xml2js](https://www.npmjs.com/package/xml2js) — Works. The output object shape is unpleasant — arrays everywhere, `$` for attributes.
- [npmjs.com/package/fast-xml-parser](https://www.npmjs.com/package/fast-xml-parser) — Found via a 2023 blog post comparing XML parsers. Cleaner API. The `isArray` callback lets you declare which tags should always be arrays, solving the single-item-vs-array ambiguity that trips up every XML parser.
- [GitHub: NaturalIntelligence/fast-xml-parser#examples](https://github.com/NaturalIntelligence/fast-xml-parser/tree/master/docs) — `parseTagValue: false` is critical. Without it the library auto-coerces strings to numbers and booleans, which breaks stop codes like `"0012"`.

**Conclusion:**
`fast-xml-parser` with `ignoreAttributes: false`, `parseTagValue: false`, and `isArray: (name) => name === 'PasoParada'`. Three options, everything else defaults.

---

## 4. UTM Coordinates — Why the Stops API Doesn't Return Lat/Lng

**Problem:** All coordinates in `GetParadasMunicipio` come back as `X` and `Y` values like `543109` and `4782340`. UTM coordinates, not lat/lng.

**Where I looked:**

- [epsg.io/25830](https://epsg.io/25830) — Confirmed: ETRS89 / UTM Zone 30N. Bizkaia sits in Zone 30N. `X` is easting, `Y` is northing.
- [npmjs.com/package/proj4](https://www.npmjs.com/package/proj4) — Standard reprojection library for browser and Node. Define source and target projections, pass in coordinates.
- [stackoverflow.com/questions/10164261/utm-to-latlng-conversion](https://stackoverflow.com/questions/10164261/utm-to-latlng-conversion) — 47 upvotes. One comment: "proj4 returns [lng, lat] not [lat, lng]". Saved a bug where stops would have landed in the wrong hemisphere.

**Conclusion:**
`proj4` with projection string `+proj=utm +zone=30 +ellps=GRS80 +units=m +no_defs`. Wrapped in a function that immediately destructures to `{ lat, lng }` to avoid future confusion on coordinate order.

Additional gotcha: some coordinate strings in the XML use comma as the decimal separator (`543109,4500`). Normalize before parsing.

---

## 5. SQLite in Node.js — Which Package

**Problem:** Needed SQLite for the stop database. `sqlite3` is async-callback based, which is awkward with async/await.

**Where I looked:**

- [npmjs.com/package/better-sqlite3](https://www.npmjs.com/package/better-sqlite3) — Synchronous API, which sounds wrong but is fine for a read-heavy server hitting an in-process file. SQLite is already single-writer, so an async wrapper adds overhead with no benefit for reads.
- [github.com/WiseLibs/better-sqlite3/issues/102](https://github.com/WiseLibs/better-sqlite3/issues/102) — A thread on concurrent reads. The maintainer explains that WAL mode + `readonly: true` on the query connection means reads never block. Exactly our pattern: one write connection for ingestion, one read-only for the API.
- [npmjs.com/package/sqlite3](https://www.npmjs.com/package/sqlite3) — Older, async, callback-heavy. Skipped.

**Conclusion:**
`better-sqlite3` with `pragma journal_mode = WAL`. Prepared statements compiled at startup for every query (`db.prepare(...)` outside route handlers) — zero query planning overhead per request.

---

## 6. Native Module Compilation on Alpine Linux (The Docker Nightmare)

**Problem:** `better-sqlite3` is a native Node.js addon that compiles against the host's C standard library. Alpine Linux uses musl libc. The prebuilt npm binaries assume glibc. This breaks.

**Where I looked:**

- [github.com/WiseLibs/better-sqlite3/issues/1050](https://github.com/WiseLibs/better-sqlite3/issues/1050) — Same problem, Alpine container. The maintainer says "use a glibc-based image."
- [hub.docker.com/_/node](https://hub.docker.com/_/node) — `node:20-alpine` uses musl, `node:20-slim` uses Debian/glibc. Switched to `node:20-slim` and the compilation worked immediately.
- The error message was clear once I read it: `prebuild-install warn install No prebuilt binaries found (target=20.20.2 runtime=node arch=x64 libc=musl platform=linux)`. The `libc=musl` is the giveaway.

**Conclusion:**
Switched Dockerfile base from `node:20-alpine` to `node:20-slim` in both builder and runner stages. Added `apt-get install -y python3 make g++` in the builder stage — `better-sqlite3` needs a C++ compiler when building from source without prebuilt binaries.

---

## 7. Choosing Hono Over Express

**Problem:** Needed a minimal HTTP server framework for Node.js. Express is obvious but Hono kept appearing in recent reading.

**Where I looked:**

- [hono.dev/docs](https://hono.dev/docs) — Hono runs on any JS runtime (Node, Bun, Deno, Cloudflare Workers). `@hono/node-server` adapter handles Node.js specifically.
- [reddit.com/r/node/comments/1b2f3a/hono_vs_express_2024](https://www.reddit.com/r/node/comments/1b2f3a/hono_vs_express_2024/) — Hono is faster in benchmarks, smaller bundle, TypeScript-first. Express has more middleware. Since this API needs no middleware (no auth, no sessions, no body parsing for SOAP — that uses raw `http`), the ecosystem gap doesn't matter.
- [github.com/honojs/hono/tree/main/packages/node-server](https://github.com/honojs/hono/tree/main/packages/node-server) — `serve({ fetch: app.fetch, port, hostname: '0.0.0.0' })`. The `0.0.0.0` binding matters in Docker — without it the server listens only on localhost and Railway can't reach it.

**Conclusion:**
Hono. A few JSON endpoints, TypeScript throughout, no middleware. `c.req.query()` and `c.json()` are pleasant to work with.

---

## 8. Finding Nearby Stops — Spatial Query vs In-Memory Scan

**Problem:** With ~1,968 stops in the database, find the nearest N to a given coordinate. Options: SpatiaLite extension, a bounding-box pre-filter, or load all stops into memory and run Haversine in JS.

**Where I looked:**

- [sqlite.org/rtree.html](https://www.sqlite.org/rtree.html) — SQLite has an R-Tree extension. `better-sqlite3` includes it but setup looked complicated for this scale.
- [github.com/nalgeon/sqlean](https://github.com/nalgeon/sqlean) — Spatial SQLite extensions. Overkill for 2k rows.
- [stackoverflow.com/questions/3695224/sqlite-getting-nearest-locations-with-haversine](https://stackoverflow.com/questions/3695224/sqlite-getting-nearest-locations-with-haversine) — 89 upvotes. The accepted answer does pure SQL Haversine but notes that without spatial indexes it's a full table scan anyway. One comment: "if you have fewer than 50k rows, just load them all and sort in app code — it's faster than you'd think."

**Conclusion:**
Load all stops into memory (`getAllStopsQuery.all()`), map Haversine distance, sort, slice. At 1,968 rows this runs in under 1ms in Node.js. The lat/lng index on the stops table exists for future use if the dataset grows.

---

## 9. The SOAP Endpoint Uses HTTP, Not HTTPS

**Problem:** Does the Bizkaibus SOAP endpoint support HTTPS? This mattered if the Android app was calling it directly.

**Where I looked:**

- Tried `https://apli.bizkaia.net/apps/danok/tqws/tq.asmx` — Connection refused. HTTP only.
- [developer.android.com/training/articles/security-config](https://developer.android.com/training/articles/security-config) — Android 9+ blocks cleartext HTTP by default. You need `android:usesCleartextTraffic="true"` in the manifest, or a `network_security_config.xml` that whitelists specific domains.

**Conclusion:**
Moved all SOAP calls to the backend. The Android app talks to Railway over HTTPS. Railway talks to `apli.bizkaia.net` over HTTP server-side. This removed `usesCleartextTraffic="true"` from the manifest. One other thing: the SOAP endpoint returns 403 without `User-Agent: Mozilla/5.0`. Found that by comparing browser DevTools headers to a bare curl request.

---

## 10. Android GPS — FusedLocationProviderClient vs LocationManager

**Problem:** Android has two location APIs: the old `LocationManager` and Google's `FusedLocationProviderClient`. Which one to use, and what the tradeoffs are.

**Where I looked:**

- [developer.android.com/training/location/retrieve-current](https://developer.android.com/training/location/retrieve-current) — Official docs recommend FusedLocationProviderClient. The `getCurrentLocation()` method (Play Services 17.1) does a one-shot fix.
- [developer.android.com/training/location/priority](https://developer.android.com/training/location/priority) — Four priority levels. `PRIORITY_HIGH_ACCURACY` uses the GPS chip and WiFi together. `PRIORITY_BALANCED_POWER_ACCURACY` uses WiFi/cell towers only. In a dense urban area where bus stops are 50–200m apart, that's the difference between the app being useful and not.
- [stackoverflow.com/questions/67386014/fusedlocationproviderclient-getlastlocation-returns-null](https://stackoverflow.com/questions/67386014/fusedlocationproviderclient-getlastlocation-returns-null) — 34 upvotes. `lastLocation` returns null if no other app has requested location recently. Fix: fall back to `getCurrentLocation()` when `lastLocation` is null, wrapped in `suspendCancellableCoroutine`.

**Conclusion:**
Three-stage fallback in `LocationTracker`: `lastLocation` first (instant, 0ms), then `getCurrentLocation` with `PRIORITY_HIGH_ACCURACY`, then `LocationManager.getLastKnownLocation` if Play Services fails. Cached fix threshold is 30 seconds — originally 5 minutes, which meant stale stops for the entire time you were walking around town.

---

## 11. ACCESS_FINE_LOCATION vs ACCESS_COARSE_LOCATION

**Problem:** `ACCESS_COARSE_LOCATION` was declared and the app seemed to work. "Seemed to work" was doing a lot of work there.

**Where I looked:**

- [developer.android.com/training/location/permissions](https://developer.android.com/training/location/permissions) — `COARSE_LOCATION` gives ~300m accuracy (three city blocks). `FINE_LOCATION` uses GPS and gives a few metres. Bus stops in dense areas can be 80m apart on opposite sides of the street.
- [stackoverflow.com/questions/68775455/difference-between-access-coarse-location-and-fine-location-android](https://stackoverflow.com/questions/68775455/difference-between-access-coarse-location-and-fine-location-android) — Confirmed: `FusedLocationProviderClient` with `PRIORITY_HIGH_ACCURACY` still only gets cell/wifi accuracy if the manifest only declares `COARSE_LOCATION`. The priority hint is ignored.

**Conclusion:**
Added `ACCESS_FINE_LOCATION` alongside `ACCESS_COARSE_LOCATION`. Android prompts for "Precise" vs "Approximate" on first run. This was the root cause of stops not updating while walking — a 300m-wrong GPS fix means a 150m movement threshold never triggers.

---

## 12. Jetpack Compose Navigation — nav3 vs Navigation Compose

**Problem:** The standard library is `androidx.navigation:navigation-compose`. Navigation3 is a newer type-safe reimplementation. Was it stable enough to use?

**Where I looked:**

- [developer.android.com/guide/navigation/navigation3](https://developer.android.com/guide/navigation/navigation3) — Navigation3 (`androidx.navigation3`) is a full rewrite: no XML DSL, sealed interface destinations with full type safety. Alpha at time of writing.
- [github.com/android/nowinandroid](https://github.com/android/nowinandroid) — Google's reference app was still on navigation-compose, not nav3. That gave me pause.
- [issuetracker.google.com/issues/338478823](https://issuetracker.google.com/issues/338478823) — Open issue: nav3 + Hilt's `hiltViewModel()`. Workaround is `rememberViewModel` from the nav3 integration artifact.

**Conclusion:**
Used Navigation3 anyway. Sealed interface destinations are a genuine improvement over string-based routes. Hilt integration works via `androidx.lifecycle:lifecycle-viewmodel-navigation3`. Both `nav3-runtime` and `nav3-ui` need importing and wiring manually.

---

## 13. Room with KSP — The Windows sqlite-jdbc Issue

**Problem:** Migrating Room's annotation processor from KAPT to KSP is supposed to be simple. It was simple — but noisy on Windows.

**Where I looked:**

- [developer.android.com/build/migrate-to-ksp](https://developer.android.com/build/migrate-to-ksp) — Replace `kapt(libs.room.compiler)` with `ksp(libs.room.compiler)`. That's it, in theory.
- [github.com/google/ksp/issues/1787](https://github.com/google/ksp/issues/1787) — KSP for Room on Windows unpacks a native SQLite library (`sqlitejdbc.dll`) into the project directory. Not harmful, but it shows up in `git status`. Fix: add `android/sqlite-native/` to `.gitignore`.
- [stackoverflow.com/questions/75318347/room-ksp-sqlite-dll-windows](https://stackoverflow.com/questions/75318347/room-ksp-sqlite-dll-windows) — 12 upvotes, confirms this is normal Windows-specific KSP behaviour. The DLL is part of KSP's incremental compilation — it runs queries against your schema at compile time to validate DAO queries.

**Conclusion:**
Kept KSP, added `sqlite-native/` to `.gitignore`.

---

## 14. Hilt DI — Compose Integration

**Problem:** Getting Hilt to inject ViewModels into Compose screens isn't automatic with Navigation3 — the docs only cover `navigation-compose`.

**Where I looked:**

- [developer.android.com/training/dependency-injection/hilt-jetpack](https://developer.android.com/training/dependency-injection/hilt-jetpack) — Covers `hiltViewModel()` for `navigation-compose`. Different mechanism with Navigation3.
- [developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-hilt](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-hilt) — Generic ViewModel+Hilt docs. More useful for understanding what's happening under the hood.
- [github.com/google/dagger/issues/3965](https://github.com/google/dagger/issues/3965) — Hilt + Navigation3 compatibility thread. A maintainer comment three-quarters down: `@HiltViewModel` works fine with nav3 if you use `lifecycle-viewmodel-navigation3`'s `viewModel()` instead of the Compose runtime's `viewModel()`.

**Conclusion:**
`@HiltViewModel` on every ViewModel, `@Inject constructor(...)` for dependencies, `hiltViewModel()` in the screens via `androidx.hilt:hilt-navigation-compose`. Both `kapt` and `ksp` plugins need to coexist — both can run at the same time.

---

## 15. Jetpack Glance for Homescreen Widgets

**Problem:** The traditional widget API (`AppWidgetProvider`, `RemoteViews`) is painful. Glance wraps it in Compose. Needed to understand how mature it was.

**Where I looked:**

- [developer.android.com/develop/ui/compose/glance](https://developer.android.com/develop/ui/compose/glance) — Official docs are incomplete. Shows basic widget creation but doesn't explain the state management model.
- [github.com/android/snippets/tree/main/compose/snippets/src/main/java/com/example/compose/snippets/glance](https://github.com/android/snippets/tree/main/compose/snippets/src/main/java/com/example/compose/snippets/glance) — Google's snippet repo. More useful than the docs page. Shows `GlanceStateDefinition` and `updateAppWidgetState` pattern with real examples.
- [medium.com/androiddevelopers/glance-api-deep-dive](https://medium.com/androiddevelopers/glance-api-deep-dive-c8bc38d9aecb) — The background refresh section: Glance widgets can't call coroutines directly. You need WorkManager or `GlanceAppWidgetManager.update()` from a coroutine scope.

**Conclusion:**
`GlanceAppWidget` + `GlanceAppWidgetReceiver` for the widget class, WorkManager for periodic background refresh. The widget reads its stop preference from DataStore. `ACCESS_BACKGROUND_LOCATION` in the manifest is only needed for the "Auto Nearest Stop" widget mode — where the widget requests location in the background. The main app only uses foreground location.

---

## 16. GitHub Actions — Android CI Failures

**Problem:** The CI workflow kept failing in 30–42 seconds, too fast to be a compile error.

**Where I looked:**

- [github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md) — Pre-installed software list for `ubuntu-latest`. API 35 is pre-installed; API 36 was not at the time of writing.
- [stackoverflow.com/questions/77234897/github-actions-android-compilesdk-not-found](https://stackoverflow.com/questions/77234897/github-actions-android-compilesdk-not-found) — Almost exactly this situation. Fix: add an explicit `sdkmanager "platforms;android-36"` step before the build, and pipe `yes |` to accept the license non-interactively.
- [gradle/actions README](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md) — The `setup-gradle` action probes the Gradle wrapper before the build step runs. If the wrapper fails to initialize (missing SDK 36), the action fails. That's why CI was dying in 35 seconds — it never reached the compile step.
- The CRLF issue: Windows commits `gradlew` with CRLF line endings. Linux runners can't execute it. Fix: `sed -i 's/\r$//' android/gradlew` before `chmod +x`, and this has to run before anything invokes Gradle.

**Conclusion:**
Removed `gradle/actions/setup-gradle` entirely (it's just for caching, not required), added the SDK 36 install + license acceptance before the build, and kept the CRLF strip + chmod. Working workflow:
1. Checkout
2. Setup JDK 17
3. `yes | sdkmanager "platforms;android-36"`
4. `yes | sdkmanager --licenses`
5. Strip CRLF from gradlew + chmod +x
6. `./gradlew assembleDebug --no-daemon`

---

## 17. Railway Deployment — Port Binding

**Problem:** The Hono server ran fine locally on port 3000. After deploying to Railway, health checks failed.

**Where I looked:**

- [docs.railway.app/deploy/docker](https://docs.railway.app/deploy/docker) — Railway requires binding to `0.0.0.0`, not `localhost` or `127.0.0.1`. A localhost bind means Railway's router can't reach the process.
- [github.com/honojs/node-server#readme](https://github.com/honojs/node-server#readme) — The `serve()` function accepts a `hostname` option. Default is `localhost`. Must be `0.0.0.0` explicitly.
- Railway's deployment logs showed `ECONNREFUSED` on the health check URL. The server was running, just not on the right interface.

**Conclusion:**
Added `hostname: '0.0.0.0'` to the `serve()` call. Also set `ENV PORT=3000` in the Dockerfile — Railway expects a `PORT` environment variable even when the value is hardcoded in the app.

---

## 18. Dead End: Trying to Use the Bizkaibus Mobile App's Internal API

**Problem:** Bizkaia has an official transit app. Could intercepting its API calls reveal a cleaner endpoint?

**Where I looked:**

- Installed the Bizkaibus Android app, set up an HTTP proxy (Charles Proxy), monitored its requests.
- The app uses the same SOAP endpoint (`apli.bizkaia.net/apps/danok/tqws/tq.asmx`) and the same `GetParadasMunicipio` REST endpoint. No private API — it's the same publicly accessible service the QR codes use.
- One additional endpoint the official app called: a REST endpoint for alerts/incidencias.

**Conclusion:**
The official app offered nothing new except the alerts endpoint, which is now in `utils/alerts.ts`. No private APIs to replicate or worry about breaking unexpectedly.

---

## 19. Dead End: React Native Instead of Native Android

**Problem:** Early in planning, considered React Native to target both iOS and Android. Expo tooling is good and I've used it before.

**Where I looked:**

- [expo.dev](https://expo.dev) — Expo simplifies RN setup. But foreground vs background GPS permissions in Expo Go vs bare workflow have been a recurring pain point.
- [github.com/expo/expo/issues/12674](https://github.com/expo/expo/issues/12674) — Background location in Expo requires a bare workflow, a custom native module, and a Play Store exemption review.
- The homescreen widget requirement ended this. React Native has no first-party widget support. Community libraries like `react-native-android-widget` exist but are unmaintained, and their APIs have nothing in common with Glance.

**Conclusion:**
Native Android + Kotlin. The widget requirement alone made it the right call. Jetpack Glance is the future of Android widgets and is genuinely pleasant once you understand the state model.

---

## 20. Keeping the Stops Database Up to Date

**Problem:** Bus stop locations are ingested once and baked into `stops.db` in the Docker image. If Bizkaia adds stops or changes routes, the database goes stale.

**Where I looked:**

- The `GetParadasMunicipio` API has no versioning or ETag support. To detect changes, you'd have to re-ingest everything and diff.
- [docs.railway.app/guides/cron-jobs](https://docs.railway.app/guides/cron-jobs) — Railway supports cron-based service triggering.

**Conclusion:**
For now, the database is static and committed to the repo. The ingestion script runs manually when a meaningful update is needed. Bus stop infrastructure doesn't change frequently in a small region. Re-ingestion takes about 90 seconds and regenerates a fresh `stops.db`. A proper solution would be a scheduled Railway cron service that rebuilds and hot-swaps the database, but that's future work.

---

*Last updated: May 2026. Update this document whenever a new library, API, or platform constraint is added to the project.*
