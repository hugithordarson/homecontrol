# HomeControl

A small WebObjects app for controlling the lights in the home, talking directly
to an IKEA DIRIGERA hub over its local REST API — no cloud, no Apple Home.

## Pairing hubs

Start the app and open `/setup`. Give the hub a name (e.g. Home, Office), enter
its IP address (find it in your router's device list or the IKEA Home smart app),
click *Start pairing*, press the action button on the bottom of the hub, and
confirm. Repeat for as many hubs as you like — pairings are stored in
`~/.homecontrol/config.json` (the old single-hub format migrates automatically).
An unreachable hub shows as a warning on the dashboard while the reachable ones
work normally; room cards are tagged with their hub's name when there's more
than one.

## What it does

- `/` — the dashboard: all lights grouped by room, as a selection surface. Tap
  lights or room names to select (Select all / Select none in the toolbar); the
  panel at the bottom controls the whole selection — on/off, brightness, warmth,
  color, lightning — through one /api/batch request. Palette chips
  (woresources/palette.json) apply on tap or can be dragged onto any light or room.
- `/floor` — the apartment floor plan (woresources/floorplan.json, mm coordinates
  traced from the architect's drawing) with live room state; click a room to
  control it and see its lights.
- `/scenes` — scene management. Arrange the lights how you want them, then
  capture the current state as a named scene. Scenes are stored locally in
  `~/.homecontrol/scenes.json`, independent of the hub's own scene storage.
- `/setup` — hub pairing.

## Hub API notes (hard-won)

- Control is PATCH `/v1/devices/{id}` with `[{"attributes": {...}}]`; the hub answers
  202 and forwards asynchronously. It validates *nothing* — unknown attributes and
  fields all get 202 — so never take a 2xx as proof a field did anything.
- An optional `transitionTime` (milliseconds, sibling of `attributes`) controls how
  fast the device moves to the new state; 0 snaps instantly. It works on brightness
  moves, but *not* on `isOn` — the on/off soft fade is baked into bulb firmware.
- Whether transition 0 actually snaps is a per-MODEL lottery — not per-generation,
  and not the hub's fault (DIRIGERA forwards transitionTime faithfully). The one
  known loser is exactly "TRADFRI bulb E27 CWS 806lm" (LED1924G9, fw 1.0.021):
  a documented firmware defect adds ~1s to every transition (zigbee2mqtt #17106).
  Everything else in the fleet strobes crisply per-bulb testing: WS bulbs on
  2.3.x/3.0.x, the 2017-gen CWS opal 600lm on 2.3.x, and notably the successor
  color bulb "E27 CWS globe 806lm" (fw 1.0.38) — so its firmware line number
  means nothing; test the model, not the version. Cross-site verdicts (2026-08):
  TRADFRI CWS globe 806lm strobes in both E27 (home ×2) and E14 (office ×6)
  variants, CWS opal 600lm fw 2.3.x strobes at both sites — but the Matter-era
  KAJPLATS E14 CWS globe (fw 1.2.0, no update available) FADES, just faster than
  the LED1924G9. So the TRADFRI CWS globe is the only color bulb verified to
  snap, and it's the outgoing line — stock up before KAJPLATS fully replaces it.
- Capabilities differ per bulb generation: the 2017 TRADFRI color bulbs (CWS opal
  600lm) have NO colorTemperature capability — canReceive lists only hue and
  saturation, so a Kelvin patch is silently skipped. Hub.applyBatch translates a
  requested temperature into the equivalent blackbody hue/saturation for such
  bulbs (ColorMath.kelvinToHueSat), so white presets work on them too.
- Rooms are renamed with PUT `/v1/rooms/{id}` (PATCH 404s).
- The hub reports attribute values it refuses to accept back: bulbs report
  colorSaturation slightly above 1.0 (e.g. 1.0022), but writing anything above
  1.0 fails. Clamp before echoing any read value into a patch.
- The temperature range fields are named in mired-think: colorTemperatureMax is
  the WARMEST Kelvin value (e.g. 2202) and colorTemperatureMin the coldest
  (e.g. 4000). Device.warmestTemperature()/coldestTemperature() translate.
- colorHue and colorSaturation must be patched TOGETHER — either alone gets 400
  ("attributes does not match any of the allowed types"). Opposite rule from
  isOn/lightLevel, which must be separate. There is no consistency; test.
- The hub can't take a burst of new connections: ~20 simultaneous TLS handshakes
  get half of them a connect timeout. DirigeraClient gates every request through
  a 6-permit semaphore per hub (plus one retry on connect failure) — fan-outs
  become a short orderly queue over warm, reused connections.
- Leave generous margin between a transition's duration and the next command to
  the same bulb — a command landing mid-transition makes the bulb snap-complete
  the ongoing one (a visible blink).
- `lightLevel` patches on a turned-off bulb are silently ignored, and a patch
  combining `isOn` with `lightLevel` applies only the `isOn` — always send them
  as separate patches, on first. A bulb switched off remembers its level at that
  moment and powers back on there, which is why effects that park a bulb at
  level 1 must repay the original brightness on the next turn-on (see the
  pending-level-restore bookkeeping in Hub).

## When the hub is "unreachable" but the network is fine

If the app logs "No route to host" for a hub that ping and curl reach happily
from the same machine, it's macOS Local Network privacy (Sequoia+) denying the
JVM: System Settings → Privacy & Security → Local Network needs the Eclipse
that's actually running (an Eclipse update = new path = new app identity = new
permission entry — the old grant means nothing), followed by an Eclipse restart.
The denied process gets a fake "No route to host", indistinguishable from a
network problem. Diagnosed 2026-08-24 after an Eclipse update; the same JBR
binary connected fine from Terminal while Eclipse-launched JVMs were refused.

## Building and running

Standard wonder-slim / vermilingua setup, same as the Vacation project:

```
mvn package
```

Run `app.AppLauncher` from Eclipse (WOLips) for development. The hub speaks
https with a self-signed certificate on port 8443; `AppLauncher` disables the
JDK http client's hostname verification accordingly.
