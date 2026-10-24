# Ljós — the HomeControl iOS app

A thin SwiftUI client for the HomeControl server: everything that matters (hub
talk, throttling, fades, schedules, bulb quirks) stays on the server, the app reads
`GET /api/state` and sends the same `/api/*` commands as the web dashboard.

- `App/` — the app: rooms, lights, scenes, palette, Siri phrases (`LjosShortcuts`)
- `Controls/` — Control Center / Lock Screen controls (a scene button, all off)
- `Shared/` — API client, models and App Intents, compiled into both targets

`project.yml` is the source of truth — run `xcodegen` (brew install xcodegen) to
create `Ljos.xcodeproj`, which isn't committed. The server defaults to
http://ljos.rebbi.is on a device and the dev app on http://localhost:1200 in the
simulator; Settings in the app overrides it. ljos.rebbi.is is a LAN name, so the
app (and its Siri/Control Center actions) work on the home network only.

Distribution: internal TestFlight on team R9H5W8A5DE, bundle id `is.rebbi.ljos`.
