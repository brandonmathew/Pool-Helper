# Pool Helper — Plan

Locked 2026-11-21 (Brandon + Mana, one question at a time). This file is the canonical plan; the same text lives in the repo as PLAN.md.

## What it is
An Android app that detects the pool table in **8 Ball Pool by Miniclip** and plays a full match by itself: it detects, aims, sets spin, sets power, and shoots — no tap to confirm.

## Locked decisions
- **Target:** phone pool game via screen capture (not a real table). Game: 8 Ball Pool by Miniclip.
- **Scope v1:** Downtown London Pub table only (first / lowest free tier). Other tables later.
- **Full match:** break, open table, solids/stripes assignment, ball-in-hand placement, and the 8-ball, end to end by itself.
- **Menu/matchmaking/rematch automation: DEFERRED.** Brandon starts the match; the bot plays from the break. The state machine leaves a lobby hook so it can be added later without a rewrite.
- **Shot capability:** direct shots, banks (multi-cushion), combos, kiss/carom and trickshots — not direct only.
- **Solver policy:** win-first with attack threshold. Every candidate (direct/bank/trick/combo/safety) is scored by expected value — pot chance under position jitter, foul/scratch risk, next-shot position. Attack when it scores best; safety when no pot clears the threshold.
- **Detection:** classical computer vision ONLY. No YOLO, no fallback model. Homography warp to canonical table space, HSV colour + solid/stripe classification, sub-pixel ball centres, ball identity by unique colour tracked across shots (no number OCR).
- **Physics:** event-based simulator (Han 2005 slide/roll, Alciatore ball-ball throw, Mathavan 2010 cushions) in pure Kotlin/JVM. Miniclip physics is proprietary, so constants are fitted from real observed trajectories.
- **Capture + input:** MediaProjection (capture only) + Accessibility `dispatchGesture` (input) ONLY. No Shizuku fallback. Android 14+ rules baked in: consent per session, single-use token, mediaProjection foreground service started before `getMediaProjection()`, capture the WHOLE display, Android 15 lock auto-stop handled.
- **First device test:** check whether Miniclip sets FLAG_SECURE — if captured frames are black, all capture methods fail equally.
- **Controls:** the app requires a FIXED in-game control setup (aim method/sensitivity/power behaviour chosen at calibration; left unchanged while the bot plays).
- **Cue:** calibrate whatever cue is equipped; guideline length is visible in capture, so a cue change is detectable.
- **Auto-calibration:** if the app detects it is uncalibrated (or the cue changed), it auto-calibrates with NO human interaction. Geometry/ball colours passively from frames; aim-drag and spin mapping calibrated on its own turn before shooting (reversible, no shot fired); power/physics constants fitted continuously from observed shot trajectories (opponent's and its own), starting from London Pub defaults, with conservative shot scoring while power-model confidence is low.
- **Device:** autodetect device specs — screen size/DPI, CPU/RAM, capture resolution, and all table/aim/power coordinates detected and calibrated as proportions of the actual screen at runtime. Nothing hardcoded to one phone.
- **Visual:** debug overlay ONLY (detected balls, warp, chosen shot line, spin/power). No floating start/stop button. Emergency stop = Stop button in the persistent MediaProjection notification, plus Start/Stop in the main app screen.
- **Build:** Kotlin + Gradle, builds by GitHub Actions. Physics/solver is a pure JVM module with no Android code so CI can unit-test it.
- **Risk (stated plainly):** MediaProjection + Accessibility + overlay is a detectable footprint carrying account-ban risk, same as Gambit Chess Helper.

## Auto-update (locked)
- App checks **GitHub Releases** itself and downloads the new APK **automatically in-app** with its own downloader — NOT Android DownloadManager, no browser.
- SHA-256 verified in-app before install. All builds signed with one stable key (GitHub secrets) so updates install over the existing app and keep data.
- Install is one user tap on the system prompt (Android blocks silent install for normal apps); everything before the tap is automatic.
- **Channel switching in-app:** Stable = releases from `main`; Beta = prereleases from the `beta` branch. Same signing key on both channels. Android limit: switching back from a newer beta to an older stable cannot install until a stable with a higher versionCode ships.
- Release notes auto-generated from merged PRs / closed issues and shown in-app.

## GitHub workflow (locked)
- Public repo `brandonmathew/Pool-Helper` (public so Actions minutes are unlimited).
- Issue for every feature, enhancement, bug, etc. Issue comments carry detailed factual info only — no conversational comments.
- An issue closes only when ALL THREE hold: code done + CI green + confirmed on Brandon's phone. Until phone confirmation it stays open labelled `awaiting-confirmation`. Commits use `Refs #N`, never `Fixes #N`.
- Commits: Conventional Commits subject (`feat:`, `fix:`, `docs:`, `test:`, `ci:`…), factual Changes/Fixes body, no conversational text, never mention the developer, no co-author trailers.
- Branching: branch per issue → PR into `beta` (CI green required) → beta builds are tested/confirmed on Brandon's phone → `beta` → `main` by PR → version tag on `main` publishes the stable release.
- Branch protection on `beta` and `main`: no direct pushes, PR + green CI required.
- Labels: `bug`, `enhancement`, `feature`, `documentation`, `ci`, `awaiting-confirmation`, `stage-1`…`stage-5`.
- Issue templates: bug report and feature/enhancement. One milestone per stage.
- Versioning (semver, fixed scheme): stable majors X.0.0 (1.0.0, 2.0.0…); stable hotfix patches X.0.N (1.0.1…); betas named after their target stable — 2.0.0-beta.1, 2.0.0-beta.2 (hotfix betas 1.0.1-beta.1). Feature branches never change the version; CI assigns the next beta version on merge into beta. Betas are GitHub prereleases. Android versionCode is a separate CI-assigned monotonic counter across both channels.

## Stages
1. **Stage 1:** repo + GitHub setup + CI; physics/solver core with unit tests; capture-only app build (MediaProjection + debug overlay) — FLAG_SECURE check + real London Pub frame capture on device.
2. **Stage 2:** live detection + overlay only, no shooting (table warp, ball detection verified on real frames).
3. **Stage 3:** practice execution — direct shots, closed-loop aim, power calibration data.
4. **Stage 4:** banks/combos/spin, break, ball-in-hand.
5. **Stage 5:** full London Pub match end to end.
