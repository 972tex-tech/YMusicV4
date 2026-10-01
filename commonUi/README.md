# `:commonUi`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

The shared Compose Multiplatform app UI (jvm + android; iOS later).

- **Screens** — drawer navigation (Discover/YT Music/Library/Downloads/Settings); Discover feed tabs + search
  (live suggestions, paged results) over `:dynamic-engine`'s `yt` task methods; LibraryScreen over `:media-store`
  flows; the persistent mini-player + full Now Playing (transport, sleep timer, capability selectors, queue).

- **SponsorBlock seam** — `ymusic.ui.sponsorblock`: `SponsorBlockApi` + `MockSponsorBlockApi` +
  `SponsorBlockCoordinator` (queue videoId → `Player.setSkipSegments`). The mock returns a 0–10s
  intro and a 60–75s mid-roll; swap `AppGraph.sponsorBlock` for the real `/sponsorBlock/getSkipSegments`
  client when that lands. Seek bars draw the ranges on `SlimSeekBar`.

- **AppGraph** — shells construct `AppGraph` (engines + `Player` facade + optional mediaStore/scan callback + the
  platform `playerSurface(modifier)` composable, which hosts the one shared video container the shell handed the
  engines) — nothing platform-specific in common. Desktop YouTube sign-in is a shell screen (`cookieSignIn`: QR /
  DevTools paste in `:desktopApp`). Mobile exposes Settings → Link devices (`exportSessionCookies` + `scanQrCode`
  + `confirmDeviceOwner`) so the phone can confirm a matching code, unlock, encrypt the jar, and POST it to that
  inbox. The desktop deletes the inbox as soon as it receives the payload.

- **PlaybackCoordinator** — play-now-with-watch-next-autoplay + display metadata for queue items.

- **Thumbnails** — render through `:image-loader` (`LocalImageLoader` provided from `AppGraph.imageLoader`; null
  keeps placeholder boxes).

- **Localization** — **localized** via compose-resources: every user-facing string lives in
  `commonMain/composeResources/values*/strings.xml` (en base + vi + ar; plurals + `%1$s` args; brand marks
  untranslated by omission — missing keys fall back to base) — never hardcode UI text (dev-only CatalogueScreen
  exempt).
  - The `AppSettings.uiLanguage` setting drives `applyUiLocale` (expect/actual; JVM/Android set the default locale,
    iOS = next launch).
  - `App` rebuilds under `key(tag)` with `LocalLayoutDirection` derived from the effective locale (RTL mirrors the
    whole UI).
  - `SettingsScreen` hosts the picker.
  - Visual check: `:desktopApp:jvmTest` LocaleSnapshotTest renders en/vi/ar PNGs.
