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

- `/` — the dashboard: all lights grouped by room, with per-light toggles and
  brightness sliders, per-room on/off, all on/off, and one-click scene buttons.
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
  means nothing; test the model, not the version.
- Rooms are renamed with PUT `/v1/rooms/{id}` (PATCH 404s).
- `lightLevel` patches on a turned-off bulb are silently ignored, and a patch
  combining `isOn` with `lightLevel` applies only the `isOn` — always send them
  as separate patches, on first. A bulb switched off remembers its level at that
  moment and powers back on there, which is why effects that park a bulb at
  level 1 must repay the original brightness on the next turn-on (see the
  pending-level-restore bookkeeping in Hub).

## Building and running

Standard wonder-slim / vermilingua setup, same as the Vacation project:

```
mvn package
```

Run `app.AppLauncher` from Eclipse (WOLips) for development. The hub speaks
https with a self-signed certificate on port 8443; `AppLauncher` disables the
JDK http client's hostname verification accordingly.
