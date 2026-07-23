# Facebook App Ads Remover

An LSPosed/Xposed module for `com.facebook.katana` that removes ads using structural DexKit discovery plus guarded, version-specific fast paths.

Current target: Facebook `571.0.0.44.73` (`473224484`), module `1.6`.

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
- The main News Feed request is a mixed GraphQL payload containing organic and sponsored units. Blocking its host or request would also block the organic feed.
- The earliest safe client boundary found so far is the dedicated story-ad source/provider layer identified by `ads_deletion`, `ads_insertion`, and `StoryAdsInDisc`. The module blocks fetch, merge, deferred-update, and insertion methods there before ad units enter feed pools.
- Game ads are not a single pipeline either. Quicksilver request hooks, postMessage hooks, and UI activity fallbacks all matter.
- Blocking `AudienceNetworkActivity` at `startActivity(...)` was too early and caused game hangs. Letting it launch and closing it immediately from activity lifecycle hooks worked better.

## Hook Strategy

### Feed / Stories / Reels

- Resolve classes with DexKit using stable strings and method shapes.
- Resolve every matching story-ad provider instead of assuming one provider class; Facebook may split this pipeline between releases.
- Install feed, Reels, and game hooks independently so a changed Reels target cannot prevent feed-source filtering from loading.
- Remove ad-backed stories from the upstream list builder append path.
- Sanitize feed CSR filter inputs and outputs.
- Sanitize late-stage feed lists before they reach rendering.
- Block sponsored entries from the sponsored pool and story pool.
- Block story ad providers by intercepting merge/fetch/update style methods.
- Keep marker-based view removal as a last-resort safety net, not the primary News Feed path.

#### Facebook 571 Findings

- Decoded feed entry points are `X.21p.Ani`, `X.baJ.Ani`, and `X.baK.Ani`; fresh list mutations pass through `X.1fM.A0B`, while `X.21O.A03` accepts sponsored-pool entries.
- One surviving ad bypassed those lists as a direct `GraphQLFeedUnitEdge`. Runtime tracing found `LithoView -> X.2Oc.A03 -> X.2OT.A05 -> GraphQLFeedUnitEdge`, whose `B3H()` category was `SPONSORED`.
- `X.2Oc.A1H` and `X.2OT.A1H` are now guarded before Litho layout. They return no layout only when the embedded edge is definitively sponsored, preventing both the visible flash and the blank row while preserving `FB_SHORTS`/Reels and organic categories.
- Fast hooks are retried around `Application.attach` and secondary-dex readiness. The class-load notifier is removed after both decoded-response and component guards are active.

### Native / Network Boundary

Facebook 571 stores most application bytecode in 18 Superpack secondary dex files. The small libraries visible directly in the APK, including `libfbunwindstack.so`, are not the feed-ad source. Networking may ultimately use native transports, but host-level blocking is too coarse because feed ads share the normal GraphQL request.

The preferred interception point is therefore after GraphQL data has been decoded but before dedicated ad providers merge it into the feed. Native or KernelSU hooks should only be considered if runtime logs show that the `ads_deletion` / `ads_insertion` provider hooks no longer resolve or fire.

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
