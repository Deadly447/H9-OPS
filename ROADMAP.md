# H9 OPS roadmap

Ideas, not promises: no dates, and the order can change. Suggestions are
welcome in the issues.

## Field use

- [ ] **Team list.** Pin your group's callsigns and see each one at a glance:
      last heard, battery (if they send telemetry), distance and direction
      from you. For nets, searches and keeping track of who's checked in.
- [ ] **Digipeater paths and "who you hear".** Lines on the map from each
      station to the digipeater that relayed it, and a small view of which
      digipeaters you hear and how often. Handy for testing an antenna or
      picking a spot to set up.
- [ ] **Topo maps.** Raster maps (USGS topo, hillshade) alongside the street
      map, for contour lines in the backcountry. Still offline, from a file
      you make once.
- [ ] **Weather warnings.** Recognise National Weather Service warning
      bulletins sent over APRS and alert on them, like emergency packets.

## Getting the app

- [ ] **F-Droid.** The app already fits F-Droid's rules (open source, no
      internet, no trackers, no non-free libraries), and its store listing
      and screenshots are in `fastlane/metadata/`. Next: submit it to
      F-Droid's build server.
- [ ] **Reproducible builds,** so anyone can check that a release APK was
      built from this source (and F-Droid can ship the same signed APK).
- [x] Obtainium install and update guide: [docs/OBTAINIUM.md](docs/OBTAINIUM.md).

## Firmware

- [ ] **One offline page that makes and flashes P2** from Chrome or Edge
      over USB, instead of the patcher plus a command-line flasher.
- [ ] **P2 for newer TIDRADIO firmware** when TIDRADIO releases one, so
      nobody is stuck needing V1.0.38.
