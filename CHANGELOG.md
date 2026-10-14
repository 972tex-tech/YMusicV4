# Changelog

## Unreleased

### Crashes / ANRs
- Fix ANR in media browser service `onGetRoot` deadlock ([2a769a633](https://github.com/ymusic-io/YMusicV4/commit/2a769a6335b51f459b12e28f65e3a9ecf008f43a))
- Fix OpenSL use-after-free on pause/flush ([c3ae35454..ce84c6702](https://github.com/ymusic-io/YMusicV4/compare/98cebef8064acb02c3dec0b446f6a6c3f23929b2...ce84c670225940b4594e6e4927f7e1272756d5bc))
- Fix PiP aspect ratio crash ([3b0c2a7ab](https://github.com/ymusic-io/YMusicV4/commit/3b0c2a7ab4ed0f50a6cf722bd3ad81d298052ca2))
- Fix foreground service start refusals ([ecdc9e20f..c84fccd88](https://github.com/ymusic-io/YMusicV4/compare/eed2ec45c181716ddad6d739a9b56ab079ec2fe0...c84fccd889bce66c31c657154dee562ee66a958f))
- Fix SAF delete of an already-missing file ([d2c1ea6b3..b7fee4322](https://github.com/ymusic-io/YMusicV4/compare/c84fccd889bce66c31c657154dee562ee66a958f...b7fee4322118717bcb50f10a59b116324c7104e7))
- Fix null SAF display name ([c44a122f9](https://github.com/ymusic-io/YMusicV4/commit/c44a122f93c740cb50158aa5580ed351122afb2e))
- Capture PlaybackCoordinator collector failures instead of crashing ([3b355f138](https://github.com/ymusic-io/YMusicV4/commit/3b355f1389fb698ee1c309710d1821bf3f61c293))

### Playback
- Fix duplicate PlaybackCoordinator: double failure reports and watch time ([b677dcdc5..8af53b329](https://github.com/ymusic-io/YMusicV4/compare/7130f48e52075a9552e7631c0f87c5ba96a39aa7...8af53b329038762d2f2a87e7492025a4e32cb657))
- Playability verdicts with Sign in for login/age-gated videos ([0139dbe47..537159152](https://github.com/ymusic-io/YMusicV4/compare/3b355f1389fb698ee1c309710d1821bf3f61c293...537159152de4df6303cc04d1947e328cbe47d34a), [5fa3feece](https://github.com/ymusic-io/YMusicV4/commit/5fa3feece2e2e7f4c7b6818b6f9e6813a6549e37))
- Captcha detection (429 / g-recaptcha) and Verify flow ([5ea5b3690..eed2ec45c](https://github.com/ymusic-io/YMusicV4/compare/68473bb9d943d6d57bc0a527b13c8c91c915fc28...eed2ec45c181716ddad6d739a9b56ab079ec2fe0))
- Prepare timeout back to 10 s, recover when engine becomes ready late ([c60ff337a..1ab33f7e8](https://github.com/ymusic-io/YMusicV4/compare/453b22a1999bd602669a70b2e56e8cf21214c73a...1ab33f7e8375dc53806bca62fa0d76a191b9a9be))
- Drop stale prewarm results ([7130f48e5](https://github.com/ymusic-io/YMusicV4/commit/7130f48e52075a9552e7631c0f87c5ba96a39aa7))
- Session cache bypass after storage failure ([70e770437..453b22a19](https://github.com/ymusic-io/YMusicV4/compare/45d91b62fa0f0503372a9a3ac942d1b79332933c...453b22a1999bd602669a70b2e56e8cf21214c73a))
- Fix StreamProtectionStatus handling: 1 ok, 2 pending, 3 invalid PO token ([2df6d3861..98cebef80](https://github.com/ymusic-io/YMusicV4/compare/6f0b43ee8544e8ab04d782f089fd8ebaafdbc419...98cebef8064acb02c3dec0b446f6a6c3f23929b2))

### Downloads
- Fail fast on playability refusal instead of player-response timeout ([b836f4af6..e1ac96106](https://github.com/ymusic-io/YMusicV4/compare/537159152de4df6303cc04d1947e328cbe47d34a...e1ac961065104e1532513fb84ffca7a2e03dff26))
- Keep SABR cursor in step with on-disk bytes ([a2c428353..6f0b43ee8](https://github.com/ymusic-io/YMusicV4/compare/5fa3feece2e2e7f4c7b6818b6f9e6813a6549e37...6f0b43ee8544e8ab04d782f089fd8ebaafdbc419))
- Treat missing player-cache files as cache misses ([bfa5b2531..45d91b62f](https://github.com/ymusic-io/YMusicV4/compare/2a769a6335b51f459b12e28f65e3a9ecf008f43a...45d91b62fa0f0503372a9a3ac942d1b79332933c))
- Move scratch files to noBackupFilesDir with orphan cleanup ([2c9b7f7e9..790e5dcd1](https://github.com/ymusic-io/YMusicV4/compare/3b0c2a7ab4ed0f50a6cf722bd3ad81d298052ca2...790e5dcd16849110b8654b13e93ee80616772dc6))
- Re-pick lost SAF folder and retry without re-downloading ([38c4666b4..35ebad576](https://github.com/ymusic-io/YMusicV4/compare/790e5dcd16849110b8654b13e93ee80616772dc6...35ebad57673ab9157a23b9d696792875ccd75371))
- Sign-in resolve dialog on Resume, Remove for unavailable videos ([47ec766ce..066db7ff0](https://github.com/ymusic-io/YMusicV4/compare/35ebad57673ab9157a23b9d696792875ccd75371...066db7ff03747829975c93a305d2b690740a43ad))
- Fix pause/resume double-run race ([c6bf07bf1](https://github.com/ymusic-io/YMusicV4/commit/c6bf07bf1af6e7943159002d7c51fc66d305d541))
- Fix duplicate file when pause lands after publish ([541d894e5](https://github.com/ymusic-io/YMusicV4/commit/541d894e55d826c5fae2c5b41a1188cf8d8fe997))
- Fix task stuck in DOWNLOADING when an end-state write fails ([d9fe968ae](https://github.com/ymusic-io/YMusicV4/commit/d9fe968ae9c073edf01517005983e7e30d1fe6e5))
- Classify socket timeouts / connection drops as network errors ([3e0bad3f7](https://github.com/ymusic-io/YMusicV4/commit/3e0bad3f7e6713e3cc6404e14796ea0108bb2687))

### Network / storage
- Mid-body read timeout no longer surfaces as a parse error ([84fa2af7b..17e9c0d99](https://github.com/ymusic-io/YMusicV4/compare/c44a122f93c740cb50158aa5580ed351122afb2e...17e9c0d99ae54badd43552ec941a4ff000344624))
- Per-status site codes for non-2xx responses ([ec85ce957..bb85e9929](https://github.com/ymusic-io/YMusicV4/compare/c1940080ee006d7bcbb1819309399de6fa5bc07a...bb85e9929701d109ffa7f6813989a0dcc8214d36))
- Upserts compatible with SQLite < 3.24 (Android ≤ 10) ([2d25bcb4e](https://github.com/ymusic-io/YMusicV4/commit/2d25bcb4e7306f0239801c8fe1b80f8fde94252d))
- DiskLruCache survives temp dir removal ([68473bb9d](https://github.com/ymusic-io/YMusicV4/commit/68473bb9d943d6d57bc0a527b13c8c91c915fc28))

### Telemetry
- Network/captcha/playability failures logged as WARN, not crash records ([c1940080e](https://github.com/ymusic-io/YMusicV4/commit/c1940080ee006d7bcbb1819309399de6fa5bc07a))
- Keep exception class names under R8 ([216e1f880](https://github.com/ymusic-io/YMusicV4/commit/216e1f8800ec2ef93e1cc21878c0c0f94d6aa70f))
- Sentry dist = versionCode ([f4b383a99](https://github.com/ymusic-io/YMusicV4/commit/f4b383a993873b6a7a8bc189afc99b86ea350a3b))
- Strip leaked tags from ANR/native crash events ([bbf6e0a1e](https://github.com/ymusic-io/YMusicV4/commit/bbf6e0a1ed9d4b1d00f05d94945d9b6039873129))
- Remote config 403 logged as WARN ([7807b77f2](https://github.com/ymusic-io/YMusicV4/commit/7807b77f2a8efe4a51a3110448514bd6131f6266))
- New diagnostics: MSE trail, base.js eval failure split E20150–E20153, E60051 stall stage, MSE reset reason ([795a70a74..0cbe58dc8](https://github.com/ymusic-io/YMusicV4/compare/066db7ff03747829975c93a305d2b690740a43ad...0cbe58dc8fc8c2db3dbfbbaa6a7a75894ad11a3e)); pot.ts E20148/E20149 ([41cc14b11](https://github.com/ymusic-io/YMusicV4/commit/41cc14b11ffa4d8fad0bd4b88e4883ae5a11d061))

### Accessibility
- Icon-only buttons announce as buttons to screen readers ([72a0bdeab](https://github.com/ymusic-io/YMusicV4/commit/72a0bdeab089aadf35c3c5e2ab7f6fb0b4718287))

### Localization
- Replace hardcoded UI labels with en/vi/ar resources ([46bca8060..30a2e1c7b](https://github.com/ymusic-io/YMusicV4/compare/72a0bdeab089aadf35c3c5e2ab7f6fb0b4718287...30a2e1c7b594a7b625162be8de195ce13f2fc91c))

## 4.0.0 ([3bac880ac](https://github.com/ymusic-io/YMusicV4/commit/3bac880acdd9598051c3c706e72d62318be3b4c3))
- First release of YMusic V4.
