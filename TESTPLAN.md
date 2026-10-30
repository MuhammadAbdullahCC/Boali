# v0.1 acceptance checks (run on the actual phone)

## P0 steps / battery
- [ ] Brand-new install: shows 0 captured steps, does not invent prior steps.
- [ ] Walk exactly 100 measured steps with phone in pocket; compare number and sensor update delay.
- [ ] Lock screen for 20 minutes; walk 500 steps; reopen; compare with a reference counter.
- [ ] Swipe app away, but do not force-stop; repeat; check low-priority tracking notification stays.
- [ ] Force-stop app in system settings; expect gap. Reopen app and document behavior.
- [ ] Reboot and reopen tracker; check no impossible leap/negative steps.
- [ ] Cross midnight on a short walk; check day changes and note delayed sensor batch attribution.
- [ ] Battery saver on/off and manufacturer-specific battery optimization rules.

## P0 GPS
- [ ] Tap Start walk while app is visible. Confirm precise GPS was granted.
- [ ] Walk a known 1 km loop. Confirm map polyline and distance; compare against reference.
- [ ] Keep screen off during route, reopen and confirm all accepted GPS positions remain saved.
- [ ] Move through a poor GPS area; ensure no multi-km straight-line jump.
- [ ] End walk; GPS tracking must stop. Step counting continues.
- [ ] Close and reopen; latest route and distance must remain visible.

## P0 offline city map
- [ ] Download Jalalpur Bhattian with Wi-Fi on.
- [ ] Wait for 'ready offline' confirmation.
- [ ] Enable airplane mode; open Lyn; pan around downloaded area at zoom 12–15.
- [ ] Route overlay must display without any upload or account.
- [ ] Zooming beyond downloaded area may require internet (expected).

## Settings / daily rewards
- [ ] Weight and stride changes affect only estimated calories and distance, not sensor steps.
- [ ] +250ml persists across process restart.
- [ ] Water reminder is skipped when >=2,000ml; Android may delay schedule.
- [ ] Goal starts at 7,000 and grows +100 each subsequent day up to 12,000.
- [ ] Milestone messages are only delivered when thresholds are actually crossed.

## CI and signed updates
- [ ] GitHub Actions completes JVM unit tests, debug APK.
- [ ] Stable keystore configured through secrets; signed release artifact appears.
- [ ] Install signed version; log steps; build next version with same signing secrets.
- [ ] Install update WITHOUT uninstalling; stored history is retained.
