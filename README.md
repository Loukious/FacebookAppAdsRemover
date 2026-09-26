# Facebook App Ads Remover

An LSPosed/Xposed module for `com.facebook.katana` that removes ads using structural DexKit discovery plus guarded, version-specific fast paths.

Original port target: Facebook `576.0.0.42.73`; development/device testing also covers `580.0.0.51.74`. Module `1.21` (versionCode 22). Discovery is primarily structural rather than based on hardcoded obfuscated class names, but an individual hook can still need revision after a Facebook update. Older versions (571 and below) are no longer supported.

## Scope

- News Feed sponsored units
- Story ads and in-disc story ads
- Reels / upstream ad-backed story append paths
- Quicksilver game ad requests
- Audience Network and Neko playable ad activities used by games

## Features

The module is more than an ad blocker, and the settings screen below is its whole surface. `ui/Toggles.kt` is the source of truth for every switch, its default and its exact effect — what follows is the map, not a second copy of it.

**Ads** (on by default)

- **Block News Feed ads** — sponsored feed posts, ad-channel requests and multi-ad units; CSR/cache sponsored protection also requires the optional feed ad guard below.
- **Block Story ads** — story ad buckets and story-player ad-break setters.
- **Block Reels ads** — Reels/Shorts sponsored units, dedicated ad fetches and Reels banners; shared in-stream/video-ad hooks require the Story ads switch too.
- **Block marketplace ads** — sponsored tiles, boosted listings and video ads in Marketplace.
- **Block game ads** — in-app game ad requests are rejected; rewarded requests resolve as success, so the reward is still granted.
- **Enable CSR feed ad guard** — extra sponsored News Feed protection on Facebook's cached feed path; follows the News Feed ads switch. AI and keyword rules remain independent.
- **Hide Reels / Marketplace / Games tabs** — three independent Navigation controls hide only the tab-bar buttons, leaving all three destinations reachable through Facebook menu, search and links. Facebook 580 uses stable `TabBarContainerLayout.onChildViewAdded` and `onLayout` callbacks with tab-icon semantic numeric IDs; the layout hook also handles icons restored/reused without the hierarchy callback. A bounded horizontal navigation-bar label matcher backs it up for alternate top/bottom layouts. It never removes pages or navigation configuration and does not affect the separate Hide Reels feed filter. All three switches default OFF. On-device verified Reels-only, Marketplace-only, and all three enabled; Facebook reflows the remaining icons rather than leaving blank slots.

The three ad-surface switches are independent, including their direct DexKit hooks. Some generic video/ad-break methods are used by both Stories and Reels, and the generic banner class scan has no reliable UI context: those shared methods block only when **all** affected surface switches are on, rather than silently blocking a disabled surface. Facebook's global ad-free-session status spoof also runs only when all five ad-family switches (News Feed, Stories, Reels, Marketplace and Games) are enabled. Consequently, Story ads alone may not suppress every sponsored circle in the Stories tray: those previously relied on the global spoof. On upgrade, the retired `ads.enabled` master setting is migrated to the new switches once; the former Reels shopping-card toggle and dedicated hook are removed.

**Feed filters** (off by default) — hide Threads posts, Reels, suggestions, People You May Know, Stories in feed, and AI-generated content (stories carrying the gen-AI transparency flag); a free-text **keyword filter**; and a News Feed **auto-refresh block**.

### Shared feed-filtering engine

`hooks/FeedFilterEngine.kt` owns the reusable `FeedItemSignals` / `FeedFilterRule` / `FeedFilterEngine` contracts and keep/remove partition logic; `FeedContentRules.kt` owns the per-toggle rule set. A new feed-content rule is defined once and can run wherever a Facebook adapter supplies its signals. `AiTransparencyInspector.kt` provides one bounded, reflection-cached TreeJNI classifier shared by all data-layer adapters. The engine reads a single setting snapshot per list/render and fails open on unknown objects or unsupported reflection shapes.

`NewsfeedFilterHook` adapts the classic `processNewStories` collection. `FeedGuardHook` adapts CSR cache input and output, late cached lists, and the Litho feed-component render hook. These four paths use the **same rule decisions**, while preserving their own Facebook-specific discovery and collection reconstruction. Other independent ad-provider, game and banner hooks are not replaced by this feed-only engine.

On Facebook 580, AI pre-render classification resolves the row's primary `GraphQLStory` through the `GraphQLFeedUnitEdge.node` virtual-model hash (`0x0033ae02`, avoiding the side-effectful `inflateFeedUnit` method) and tests the affirmative detected/self-disclosed metadata children. The plugin's `gen_ai_transparency_label_info` field (`0x39dd8998`) is **not** sufficient for filtering: many ordinary stories have this subtree, including a default `AI content` title. An initial experimental presence/title classifier removed nearly the whole CSR batch and was rolled back. Only affirmative AI Boolean metadata removes a post at the data/render layers; unknown story shapes fail open. On-device after the correction, both `pipeline=LITHO_RENDER removed=1 rule=AI_CONTENT` and `pipeline=CLASSIC ... rules={AI_CONTENT=1}` were observed, with the UI fallback reporting no hide on those passes. The CSR adapter also continues removing ordinary sponsored and Reels entries; not every AI-labeled post necessarily exposes the metadata on every path.

The AI-label UI fallback remains supplemental: Facebook/Litho exposes its displayed badge through virtual accessibility nodes, not ordinary Android `View` children. The bounded post-mount provider traversal previously logged `virtualMatches=1` and `UI fallback hid AI-content feed row`, and an after-filter UI XML dump no longer contained that label. Do not build accessibility-node trees during Litho mount/layout. Where the affirmative GraphQL signal exists, the upstream classifier now blocks the row before this fallback is needed.

Runtime rule counters use `FBAR.Filter` with `pipeline=CLASSIC`, `CSR_CACHE`, `LATE_CACHE` or `LITHO_RENDER`, and include `aiEvaluated`, removed totals and matched rule IDs. The cache adapters protect only paths whose hooks *actually installed*; a `FeedGuard: cached install: 0 hook(s)` line means those cached-path hooks are **not active**. This refactor intentionally uses a new `feed.guard.classes.rule-engine-v2` discovery key to trigger one new DexKit pass instead of trusting the older sponsored-only class cache on Facebook 580. Re-check installed hook counts after each Facebook update.

**Update resilience (FB 580 baseline):** `FeedHookSignatures` resolves CSR and late-cache methods from bounded argument-role combinations rather than obfuscated method names or fixed `ImmutableList` positions. Known 3-/4-argument signatures take precedence; unique alternatives up to seven arguments are supported. `FeedLithoSignatures` requires the component and wrapper to share one plausible context argument and supports bounded 1–6-argument render methods, including a shifted context parameter. Ambiguous candidate sets fail open rather than risking unrelated hook methods. These resolvers do not make arbitrary Facebook schema changes automatically compatible.

The shared `MethodCache` now validates Facebook version, module version and an explicit discovery schema, and rejects a snapshot if any cached method no longer resolves. A full discovery snapshot clears obsolete class entries. FeedGuard re-resolves every cached class's method *shape* and verifies CSR/late/Litho role counts; a stale or partial installation invalidates its sentinel and triggers a clean discovery on the **next** Facebook process, retaining whatever succeeded in the current process. A transient discovery exception is marked for retry, never recorded as a permanent `absent` result. Log `FBAR.Filter health stage=cache-install|full-scan|20s|90s` reports `NO_HOOK`, `HOOK_INSTALLED_NOT_INVOKED`, `INVOKED_NO_ITEMS`, `INVOKED_NO_REMOVALS` or `REMOVING`, plus separate installed/invoked/inspected/AI/removal counts. An installed hook alone is not evidence that it processes feed items.

**Stories** — view stories without marking them seen; keep the Stories tray out of the feed.

**Appearance** — force dark mode; optional **AMOLED black mode** ports Morphe's true-black theme logic, turning Facebook's dark neutral background palette into pure black while leaving dividers, text, colored surfaces, images and light mode alone.

**Privacy** — allow screenshots and recording; block Facebook's own capture detection.

**Navigation** — activity list: dump every hidden activity Facebook starts (action, URI, extras) to the log.

**Links** — unwrap `facebook.com/l.php?u=…&fbclid=…` redirect links to the real destination before the browser opens them.

**Downloader** — contextual download actions for the reel or story you are actually viewing, with the existing quality picker bound to that media ID; **Copy URL** copies only the currently selected quality; hand media to the browser instead of the in-module downloader; quick-download from a copied link. Reels get native **Download** and **Repost** sidebar buttons, and stories get **Repost** in the three-dot menu. Repost reuses the existing Facebook Page posting flow (the page list comes from `graph.facebook.com`, the token from the live session).

The reel button is built with Facebook's own sidebar factory. Discovery fingerprints the sidebar's name and call structure rather than obfuscated class names; the factory signature validator accepts the known argument layout plus additional trailing boolean feature flags, preserving their live values from the reel being rendered. A changed meaning/order of the existing arguments fails closed with a log instead of calling the wrong factory. Reel discovery failures are retried after 72 hours (or immediately on a hook-schema or Facebook version change), and a failed cached hook is invalidated for rediscovery at the next Facebook start. See `ReelFactorySignatureTest` for 578/580 and forward-compatibility cases.

**Video** — optional video resume (**OFF by default**): saved-position restoration is a one-shot operation when a video opens; short Reels (including saved points around one second) get an earlier restore decision. Facebook's pooled player also seeks with `BY_AUTOPLAY` on revisits, sometimes resetting to zero: this is not treated as a user scrub, its pre-reset position is preserved, and a genuine Reel departure permits a new one-shot session. Genuine forward/back scrubbing cancels a pending restore and cannot rearm it through Facebook's repeated playback-start events. Seeking to the beginning clears an old saved point; a natural subsecond stop does not. Background playback with no floating window is separate; its stale-position clamp does not rewrite foreground manual seeks. An explicitly saved ON preference is preserved when upgrading; turn it OFF in settings to disable it on existing installations.

**Account** — session export/import. **Module** — launcher-icon visibility. Both are described below.

## Settings App

The module ships its own Android app: one scrolling screen of switches. Open it from the launcher icon, or from the Xposed/Vector module list (Modules → Facebook App Ads Remover → settings).

The settings Activity uses Material 3 DayNight and Android 12+ **Dynamic Color** (wallpaper-derived system palette). Its switches, buttons, dialogs and system bars follow the active light/dark scheme; older devices retain the built-in theme fallback. This applies only to the module settings Activity, not Facebook's own theme or the AMOLED hook.

**How a toggle reaches a hook.** The switches are not stored in the module app. They live in the framework's remote-preferences group `fbar_settings` (the LSPosed/Vector daemon database), which the module app writes through the libxposed *service* library and the hooks read inside the Facebook process through `XposedInterface.getRemotePreferences`. Plain `SharedPreferences` files in either app's storage are **not** part of that channel — the daemon never reads them. Until the service binds, the switches render with their defaults and stay disabled; the header line says so. Several hooks read their toggle once at install, so treat a change as **apply on next Facebook restart**.

Earlier builds kept toggles in a Facebook-process-local `fbar_prefs` file (written while verifying on device with root). The first time the settings app binds, it copies that file into the remote group if the group is still empty; after that the remote group wins.

### Session export / import

The **Account** section holds buttons rather than switches. *Export session* copies Facebook's two live session files (`authentication`, `logged_in`) plus the captured cookies to `Download/FacebookAppAdsRemover` as `FBAR-Session-*.json`. *Import latest* restores the newest export; *Import from file…* lets you browse to any of those JSON files, for instance one copied from another device. Force-stop Facebook afterwards.

The buttons broadcast into the Facebook process, where `core.SessionBackup` answers — that side holds the session files and the captured cookies.

### Hiding the launcher icon

The **Module** section's `Show launcher icon` switch takes the app out of the launcher. It is the odd one out on the screen: its value lives in `PackageManager`'s component state rather than the framework's remote preferences, so it needs no service and is enabled immediately. Hiding sits behind a confirmation dialog, because the icon is the normal way in.

The drawer entry is an `<activity-alias>` (`ui.LauncherAlias`), not the activity itself, so hiding the icon disables only that component. `ui.MainActivity` stays exported and startable by explicit intent, which is the way back:

```powershell
adb shell am start -n tn.loukious.facebookappadsremover/.ui.MainActivity
```

The manifest also carries an always-enabled second alias, `ui.InfoAlias`, with `ACTION_MAIN` + `CATEGORY_INFO`. `PackageManager.getLaunchIntentForPackage()` resolves `CATEGORY_INFO` before `CATEGORY_LAUNCHER`, and the Xposed module list opens a module's settings through exactly that call — without the second alias, hiding the icon would take the manager's settings button with it.

Two consequences worth knowing:

- Pixel Launcher's **search** does surface the `CATEGORY_INFO` entry (the app drawer does not — it queries `CATEGORY_LAUNCHER` explicitly). A search hit renders as package information, so tapping it opens *App Info* rather than this screen. App Info's own *Open* button does land on the settings screen.
- Hiding the icon cannot affect the module itself. The framework daemon loads the module from the APK path in its own database, so the hooks inside Facebook are untouched.

## Main Findings

- Obfuscated names such as `AiD`, `A84`, `A8t`, `ADF`, or `Aue` are too unstable to hardcode. They changed across builds and caused broken hooks.
- Stable strings and structural signatures are much more reliable than direct obfuscated names.
- Feed ads are inserted at multiple layers. Blocking only one layer is not enough.
- The main News Feed request is a mixed GraphQL payload containing organic and sponsored units. Blocking its host or request would also block the organic feed.
- The earliest safe client boundary found so far is the dedicated story-ad store layer identified by `AdsPaginatingNetworkAdBucketFetcher`, `FbStoryAdInDiscStoreImpl`, `IN_DISC_METADATA_KEY`, and `AD_BUCKETS_KEY`. The module blocks fetch, merge, deferred-update, and insertion methods there before ad units enter feed pools. The telemetry labels `ads_deletion`/`ads_insertion` are deliberately NOT used as class selectors anymore: unrelated story viewer classes log those labels, and hooking them blanks the story viewer (576's `X.BAl` was the story viewer's own `onDataChanged` handler).
- Game ads are not a single pipeline either. Quicksilver request hooks, postMessage hooks, and UI activity fallbacks all matter.
- Blocking `AudienceNetworkActivity` at `startActivity(...)` was too early and caused game hangs. Letting it launch and closing it immediately from activity lifecycle hooks worked better.
- `com.facebook.soloader.SoLoader` is not an ad class, but it *names* ad libraries. It holds the merged-native-library dispatch table, which lists every native library in the app — including ad-related ones such as `libmailboxinthreadadcontextbannerjni.so` — so a DexKit string-anchor scan for an ad-library name matches it. Sweeping it is fatal: its `loadLibrary` / `loadLibraryUnsafe` overloads return boolean, so a false-returning hook replaces them, no merged library ever gets its `JNI_OnLoad`, every `initHybrid` throws `UnsatisfiedLinkError`, and Facebook cannot start at all. Loader infrastructure must never be an anchor target — see the guard under Cache Invalidation.

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

#### Facebook 576 Findings

- The feed component pair (576: wrapper `X.2q8`, component `X.2q4`) is discovered by Litho component name, not by obfuscated class name. Litho generated components pass a stable spec name to their base class constructor — `"NewsFeedFeedUnitComponent"` for the feed unit component and `"LoggingComponent"` for the generic wrapper Litho renders feed units through — and those strings survive Facebook's obfuscator. The class-load notifier reads the name reflectively (the generated base stores it in a final String field filled by a String constructor; the class is instantiated through its no-arg constructor to read it), and the full DexKit pass finds the same classes with an exact `usingStrings` match as a backstop.
- The cached initial News Feed (including a sponsored slot) assembles and renders within ~2s of a cold start — before any DexKit scan can finish. To win that race, the discovered guard pair is persisted in the host's `cacheDir` keyed by the Facebook version, and later launches load it right after `Application.attach`. The cached class names still fail `Class.forName` at attach time (the secondary dex is not configured yet), so every timed guard attempt re-tries registering them; the guard then installs ~200ms after attach, before the cached feed renders. This is what removes the "second feed item is a sponsored post" on force-close/reopen. A Facebook update changes the version key and falls back to the DexKit discovery, which then rewrites the cache.
- The wrapper renders via `A1F` only (no `A1H`), so the guard matches Litho layout entry points by shape — instance methods taking the Litho context (`X.3Qp`) with a non-primitive return — instead of requiring a method literally named `A1H`. Static builder factories with the same shape are excluded.
- The edge and wrapper-child fields are resolved structurally: the component's edge field is the one whose type is (or implements) the feed-item contract exposing `GraphQLFeedStoryCategory` (576: `X.3yV` via `B9B()`); the wrapper's child field is the one assignable to the component class.
- `StoryAdsInDisc` no longer exists anywhere in 576, and the story ad store moved to `X.BEC`. See the selector change above.
- The feed-item contract hooks (`X.3YX`/`X.3Xk` on 576) and the CSR/network/pool hooks all resolve structurally via DexKit (`X.21r` CSR filters, `X.21e` sponsored pool, `X.BEC` story ad store, late feed list hooks). The hardcoded 571 contract-class hints (`X.3YX`/`X.3Xk`), the Audience Network listener names (`X.mGv`/`X.mGo`), the Quicksilver handler name (`X.edO`), and the `X.2Jy` feed-object hint were removed entirely — the inspector and edge-field resolution work structurally (GraphQL edge class name, `GraphQLFeedUnitEdge`/`GraphQL`+`Feed` name matching, feed-story-category enum constants), and the AN reward no longer depends on them since the webview-delivery rewrite delivers the reward.
- The 571 hardcoded fast paths (`X.21p.Ani`, `X.1fM.A0B`, `X.21O.A03`, `X.2mm.A3F`, `X.1vr.addNewEdgeToCollection`, the `X.9xH`-style curated story-ad class list) were removed: they were all dead on 576, and the curated list even matched a network-connectivity helper (`X.9xH`) whose shape coincidentally fit the deferred-update rule. The seeded component guard seeds (`X.2q4`/`X.2q8`) were removed with the Litho-name discovery above.
- The global `addView` safety-net hook must never call `View.createAccessibilityNodeInfo()` on freshly added views. On 576, building the accessibility node mid-mount runs Facebook's custom-view accessibility code with side effects, and page-profile header text ("Sign up", "Followers", "posts") ends up blank after pull-to-refresh. `collectViewMarkerTexts` therefore reads only `contentDescription` and `text`.

### Native / Network Boundary

Facebook 571 stores most application bytecode in 18 Superpack secondary dex files. The small libraries visible directly in the APK, including `libfbunwindstack.so`, are not the feed-ad source. Networking may ultimately use native transports, but host-level blocking is too coarse because feed ads share the normal GraphQL request.

The preferred interception point is therefore after GraphQL data has been decoded but before dedicated ad providers merge it into the feed. Native or KernelSU hooks should only be considered if runtime logs show that the `ads_deletion` / `ads_insertion` provider hooks no longer resolve or fire.

### Game Ads

- Resolve Quicksilver ad request methods by their stable JSON error strings.
- Hook the Quicksilver `postMessage(String, String)` bridge as a second request-layer fallback.
- The runtime delegate the game webview actually uses (576: `X.q10`) is NOT the DexKit-discovered service delegate (`X.gJA`); it is caught at registration by hooking `WebView.addJavascriptInterface`. It is a thin delegate with no promise-resolve helper on its class, so request payloads can only be snapshotted there, not resolved.
- The promise result is delivered back into the webview as `evaluateJavascript("e = new Event('message');e.data = {...};window.dispatchEvent(e);")`. The module rewrites that JSON in place: for rewarded requests (`getrewardedvideoasync`/`getrewardedinterstitialasync`, and `showadasync` with a rewarded ad instance) error fields are dropped and `success/completed/didComplete/watched/rewarded` + `completionGesture:"post"` are forced, so the game grants the reward with no ad shown. The rewrite also covers `loadUrl` and `postWebMessage` deliveries. Note the envelope `type` stays `"rejectpromise"` on the rewritten `showadasync` responses — the game reads the outcome fields in `data`, and converting the envelope is unnecessary.
- Close `AudienceNetworkActivity`, `AudienceNetworkRemoteActivity`, and `NekoPlayableAdActivity` from lifecycle hooks as UI-level fallbacks.
- Only hard-block the playable activity launch path directly; Audience Network activity launches are allowed so their internal close/error flow can run before the activity is closed.

### Cache Invalidation

Two discovery results are persisted **inside the Facebook process**, and both are keyed against the build that produced them:

| Cache | File | Key | Rebuilt when |
|---|---|---|---|
| Method / discovery | `fbar_discovery_cache` in the host `cacheDir` | Facebook `versionCode` | the host version changes |
| Banner classes | `fbar_prefs_banner` in the host `shared_prefs` | Facebook `versionCode` **and** module `VERSION_CODE` | either stamp moves |

- **Why the banner cache needs the module stamp as well.** Its entries are obfuscated member names, so they mean something only for the exact host build — that is the host stamp. But the *scan's own semantics* change with the module, and that is precisely how a poisoned class set (the SoLoader entry) reached a shipped cache and stayed there: the list was written once and afterwards only ever read, so nothing could revise it. Stamping both makes the module version part of the cache's validity.
- **A stale cache is rebuilt only when a DexKit bridge is available.** On the discovery-cache-hit launch path there is none (`ModuleMain` passes a null bridge there), so a stale set is swept as it stands and the stamps are deliberately left stale: a name that no longer exists simply fails `Class.forName` and is skipped, and the loader guard makes a poisoned entry harmless. Wiping the set there would strand banner coverage until Facebook's data was cleared — deferring moves the rebuild to the next launch that does have a bridge.
- **The class set is filtered twice**: once at scan time, so a poisoned name is never written to the cache in the first place, and once before the sweep, so a cache written by an older module is harmless anyway. The sweep also never replaces `loadLibrary` / `loadLibraryUnsafe`, whichever class it lands on.

## Notes About Logs

- Runtime logs go through `core/L.kt`, which writes to logcat and to the framework's module log.
- Read them with `adb logcat -s FacebookAppAdsRemover FBAR.Discovery`; every hook has its own `FBAR.*` tag.
- Logging is currently **not** gated behind `BuildConfig.DEBUG` — release builds are as loud as debug ones. Gating it is an open item.

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

`versionCode` / `versionName` live in `app/build.gradle.kts`; the output lands in `app/build/outputs/apk/debug/FacebookAppAdsRemover-v<versionName>-debug.apk`.

## Current Direction

- Prefer stable strings, type signatures, and runtime structure over obfuscated identifiers.
- Gate the runtime logging behind `BuildConfig.DEBUG` (open item — nothing gates it today).
- Treat feed, story, and game ads as separate pipelines with separate fallbacks.
