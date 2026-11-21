# Pool Helper

An Android app that detects the pool table in 8 Ball Pool by Miniclip and plays a full match by itself: it detects the balls, aims, sets spin, sets power, and shoots.

- **v1 target:** Downtown London Pub table only
- **Detection:** classical computer vision (no ML models)
- **Capture / input:** MediaProjection for capture, Accessibility gestures for input
- **Calibration:** fully automatic — no manual calibration steps
- **Updates:** in-app auto-update from GitHub Releases, with a stable/beta channel switch

The full locked design is in [PLAN.md](PLAN.md).

## Build

Builds run on GitHub Actions. Locally:

```
./gradlew :core:test :app:assembleDebug
```

- `core` — pure Kotlin/JVM physics, rules, and solver. No Android code; unit-tested in CI.
- `app` — the Android app (capture service, debug overlay, executor, updater).

## Versioning

- Stable: `1.0.0`, `2.0.0`, … with hotfix patches `1.0.1`, …
- Beta: named after the target stable — `2.0.0-beta.1`, `2.0.0-beta.2`, …
- Feature branches never change the version; CI assigns the next beta version on merge into `beta`.
