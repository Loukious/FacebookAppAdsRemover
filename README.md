# Facebook App Ads Remover

An LSPosed/Xposed module for `com.facebook.katana` that removes ads from the Facebook app without relying on hardcoded obfuscated class or method names.

## Scope

- News Feed sponsored units
- Story ads and in-disc story ads
- Reels / upstream ad-backed story append paths
- Quicksilver game ad requests
- Audience Network and Neko playable ad activities used by games

## Main Findings

- Obfuscated names such as `AiD`, `A84`, `A8t`, `ADF`, or `Aue` are too unstable to hardcode. They changed across builds and caused broken hooks.
- Stable strings and structural signatures are much more reliable than direct obfuscated names.
- Feed ads are inserted at multiple layers. Blocking only one layer is not enough.
- Game ads are not a single pipeline either. Quicksilver request hooks, postMessage hooks, and UI activity fallbacks all matter.
- Blocking `AudienceNetworkActivity` at `startActivity(...)` was too early and caused game hangs. Letting it launch and closing it immediately from activity lifecycle hooks worked better.

## Hook Strategy

### Feed / Stories / Reels

- Resolve classes with DexKit using stable strings and method shapes.
- Remove ad-backed stories from the upstream list builder append path.
- Sanitize feed CSR filter inputs and outputs.
- Sanitize late-stage feed lists before they reach rendering.
- Block sponsored entries from the sponsored pool and story pool.
- Block story ad providers by intercepting merge/fetch/update style methods.

### Game Ads

- Resolve Quicksilver ad request methods by their stable JSON error strings.
- Hook the Quicksilver `postMessage(String, String)` bridge as a second request-layer fallback.
- Resolve ad requests as a no-op success payload where possible so the caller does not hang waiting on the bridge.
- Close `AudienceNetworkActivity`, `AudienceNetworkRemoteActivity`, and `NekoPlayableAdActivity` from lifecycle hooks as UI-level fallbacks.
- Only hard-block the playable activity launch path directly; Audience Network activity launches are allowed so their internal close/error flow can run before the activity is closed.

## Notes About Logs

- Runtime logs are debug-only.
- `Patches.kt` and `Module.java` now gate logging behind `BuildConfig.DEBUG`.
- Release builds should stay quiet unless you re-enable logging yourself.

## About `feedCsr=0`

The startup line:

```text
DexKit groups: ... feedCsr=0 ...
```

is normal in the current implementation.

That number is only the result of the initial batch string-group search. Feed CSR hooks are also resolved by later structural and fallback matchers, so `feedCsr=0` does not mean feed CSR filtering is disabled.

The line that actually matters is:

```text
Resolved feed CSR filters=...
```

If that later line contains resolved classes, the CSR filtering path is active even when the earlier batch count is zero.

## Build

- Android app module: `app`
- Host package: `com.facebook.katana`
- Application ID: `tn.loukious.facebookappadsremover`

Build the debug APK with:

```powershell
./gradlew :app:assembleDebug
```

## Current Direction

- Prefer stable strings, type signatures, and runtime structure over obfuscated identifiers.
- Keep debug instrumentation available in debug builds only.
- Treat feed, story, and game ads as separate pipelines with separate fallbacks.
