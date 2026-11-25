# Changelog

## 1.1.1

- No changes to the app. Store listing and screenshots for F-Droid
  (`fastlane/metadata`), an Obtainium guide, the roadmap, and the P2
  firmware patcher are in this release.

## 1.1.0

- **Map tab:** stations on an offline map (a Protomaps PMTiles file for your
  area), with details, Navigate, trails, full screen, and a one-line radio
  card while it's open. **⋮ → Map file… → Get a map for your area…** gives the
  exact command for your area, sends it to your computer, and links to the
  free pmtiles tool and the guide ([MAPS.md](MAPS.md)). The app still has no
  internet access. Without a map, stations show on a plain grid.
- **Map overlays:** GPX, KML and KMZ trail plans and search areas.
- **Smart timed positions:** 🚗 Smart and 🚶 Smart in 📍 → Every….
- **Weather:** 30-day history of the weather stations heard, as cards with
  the nearest town and temperature, and small graphs.
- **Alerts** (watched callsigns, areas, emergency packets), **objects** and
  **bulletins**.
- **Two rows of tabs and buttons**, a shorter ⋮ menu, and **Settings → Tabs
  and shortcuts…** to arrange them.
- Packet history kept for 30 days; export by time range.

## 1.0.0

First public release.

- Lists the APRS stations a TD-H9 hears, with a raw packet log, station
  details (position, weather, telemetry, recent packets) and exports.
- APRS text messaging through the radio: acknowledgements, retries, delivery
  ticks, quiet mode and NOGATE.
- Send your position to one station as a message, exact or rounded, once or
  on a timer.
- Track recording with GPX export.
- Radio card: link state, battery, and channels A and B (frequency, channel,
  power, bandwidth, APRS), read from the radio every minute.
- Radio PIN, TX delay, developer log and night red.
- Needs H9 OPS firmware P2 on the radio.
