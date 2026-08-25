# Eddies agent guide

A local-first crypto portfolio tracker for Android. No account, no server of its
own, no telemetry. Holdings live only on the device, in a SQLCipher-encrypted
database, and leave it only as a passphrase-encrypted backup the user writes
deliberately.

Name: **Eddies** presentationally, `eddies` everywhere technical. It is Cyberpunk
2077's slang for eurodollars, which fits: EUR and USD are the two currencies that
ship first.

## Build and verify: Docker only

There is no Android toolchain on the host in normal use. Build and test through
the container. Do not run `./gradlew` or `adb` directly on a dev box.

```
make test    # :app:testDebugUnitTest, the fast gate. Run this first
make build   # debug APK  -> build-output/eddies-debug.apk
make lint
make release # release APKs (signed if keystore.properties/env present)
make shell   # container shell for one-off gradle tasks
```

Definition of done: `make test` passes, and for anything touching UI or the
build, `make build` produces an APK. **Check the timestamp it prints.** Never
infer a successful build from a successful `make test`.

`make build` is the target; `app` and `debug` are aliases. That is not cosmetic:
`app/` and `build/` are real directories, so without the `.PHONY` declarations
`make app` decides it is up to date, prints *"Nothing to be done"* and exits 0.
`docker/run-gradle.sh` therefore ends by listing every APK in `build-output/` as
`fresh` or `STALE`, and a mistyped goal exits non-zero.

CI is **GitHub Actions**, not Gitea. This is the first project here on GitHub, so
the workflows were ported rather than copied, and three differences are
load-bearing: `actions/upload-artifact` must be `@v4` (v3 was shut down in
January 2025 and hard-fails), the release job needs an explicit
`permissions: contents: write`, and releases are published with `gh release
create` rather than the Gitea API curl dance.

## The market-data contract. Read this before touching `data/price/`

These are properties of the services, verified live on 2026-08-25, not choices we
are free to revisit. All of them are public and keyless, so re-verify with `curl`
rather than trusting this file.

### Kraken WebSocket v2 uses market symbols, and its own REST metadata lies

`wss://ws.kraken.com/v2`, subscribe with
`{"method":"subscribe","params":{"channel":"ticker","symbol":["BTC/EUR"]}}`.

It wants **BTC/EUR**. Sending `XBT/EUR` is answered with
`{"error":"Currency pair not supported XBT/EUR","success":false}`. Same for
`XDG` against `DOGE`.

The trap: REST v1 `/0/public/AssetPairs` returns a field literally called
`wsname` whose value for that pair is `"XBT/EUR"`. That is the **v1** socket
name. Using it means BTC never receives a price, the app quietly falls back to
the REST aggregator, and the symptom is "Kraken feels slow" rather than
"Kraken is broken".

`KrakenWsSource` therefore builds symbols from the `base` and `quote` fields
(which arrive as legacy `XXBT` / `ZEUR` and are normalised by
`KrakenSymbols.toMarketSymbol`), never from `wsname`.

Ticker payloads carry prices as **bare JSON numbers** (`"last":68035.6`).

### Binance sends the whole market as strings

`wss://stream.binance.com:9443/ws/!miniTicker@arr` streams every symbol in one
connection at roughly 1 Hz, so the number of coins held changes nothing about the
connection and there is no subscription list to leak. Everything not held is
discarded on receipt.

Prices arrive as **strings** (`"c":"79403.15000000"`). The mini-ticker has no
percentage field, so 24h change is computed from `o` and `c`.

Binance quotes almost everything against USDT rather than fiat, so a EUR user
pays one FX conversion per asset. That is why Kraken is the default.

**The two feeds disagree on encoding, which is why nothing goes through a
Double.** `JsonElement.asBigDecimal()` reads `JsonPrimitive.content`, the raw
text, so a bare number and a quoted string are both exact.

### CoinPaprika needs no key and quotes EUR natively

`/v1/coins` lists everything; `/v1/tickers/{id}?quotes=EUR` prices one coin with
`quotes.EUR.price` and `percent_change_24h`. Commercial use is permitted without
a key, which is why it is the default aggregator.

Per-coin calls, not the bulk `/v1/tickers`: the bulk endpoint returns every asset
it tracks, which is megabytes for a handful of holdings.

### CoinGecko is opt-in and needs the user's own key

No key ships in the APK. An embedded key is extracted within a day and then
shared by every install until it is revoked. The user pastes their own, and it is
sealed by `SecretStore` before it touches DataStore.

### Frankfurter is the FX source, and it is daily

`https://api.frankfurter.dev/v1/latest?base=EUR&symbols=USD,...` returns
`{"amount":1.0,"base":"EUR","date":"...","rates":{"USD":1.1662}}`. Keyless, MIT,
self-hostable, sourced from the ECB.

**EUR is the pivot because the ECB publishes against EUR.** Rates are cached per
day and refreshed at most every 12 hours: the ECB publishes one reference set per
working day, so polling harder returns the identical number and only tells a
third party how often the app is open. `FxRepository.rateOn` reads the last rate
published *on or before* a date, because weekends and holidays have no
publication and must not read as a gap.

### Historical prices, and how deep each source goes

Verified 2026-08-25. All keyless.

- **Kraken** `/0/public/OHLC?pair=<altname>&interval=1440`. 720 candles, so about
  two years of daily. **It speaks REST v1, not the socket dialect**: it wants
  `XBTEUR` and keys the reply `XXBTZEUR`. Sending the v2 socket's `BTC/EUR`
  returns "Unknown asset pair" and the chart silently stays empty, which is the
  `wsname` trap in the other direction. `KrakenSymbols` therefore carries both
  mappings, and `KrakenHistorySource` reads the reply by taking the single
  array-valued key rather than reconstructing the name.
- **Binance** `/api/v3/klines?symbol=<sym>&interval=1d&limit=1000`. 1000 candles,
  about 2.7 years, the deepest of the three. Values are strings. Quoted in USDT.
- **CoinPaprika** `/v1/tickers/{id}/historical?start=<date>&interval=1d`. **The
  free tier is a rolling one year**: anything older is refused with
  `{"error":"Getting daily historical data before ... is not allowed in this
  plan"}`, and that error is a JSON object where an array is expected. The start
  date is clamped rather than sent optimistically, or the chart ends up empty
  instead of merely short.

So long-tail coins get one year of history where exchange-listed ones get two or
more. Nothing in the UI pretends otherwise; the chart starts where the data does.

Both delta cleanly: Kraken takes `since` in seconds, Binance `startTime` in
millis. A seven-day delta is about 640 bytes against 61 KB for the full pull.

### Koios, for Phase 2 staking

`api.koios.rest`, public tier is keyless at 5000 requests/day, and
`account_rewards` returns full reward history for a stake address. Not yet wired
up; `AccountEntity.stakingAddress` and `TxType.STAKING_REWARD` exist so it is an
addition rather than a migration.

## Architecture

```
app/src/main/java/com/eddies/app/
  core/
    design/    Color, Theme, Type, ThemeMode, ChartGeometry (pure), Charts (Canvas)
    crypto/    SecretStore (Tink), DatabaseKeyProvider (SQLCipher key)
    backup/    BackupCrypto (passphrase envelope, pure javax.crypto)
    ui/        AssetIcon, PnlText, Section, IconResolver
    result/    AppResult, AppError
  data/
    db/        Converters, EddiesDatabase, dao/Daos.kt, entity/Entities.kt
    prefs/     SettingsDataStore (one aggregated AppSettings flow)
    price/     PriceRepository (the merge point), Kraken/Binance/Aggregator sources, FxRepository
    repo/      Asset, Transaction, Portfolio repositories
    backup/    BackupManager, BackupModels, CsvExchange
  domain/      PositionCalculator, PortfolioBuilder, FiatConverter, BackoffPolicy,
               AssetIdentity, MoneyFormat, Ledger      (all framework-free)
  di/          DatabaseModule, NetworkModule, WorkModule
  feature/     portfolio/ insights/ markets/ assetdetail/ addtransaction/
               accounts/ settings/ backup/ about/ lock/
  navigation/  Routes, EddiesNavHost, RootViewModel
  work/        DailyWorker, WorkScheduler
app/src/main/assets/  coins/*.png (389), asset_seed.json (600 coins)
app/src/test/         mirrors the main tree. 96 tests
```

`domain/` is where the value is. Everything numeric or decision-making lives
there as pure Kotlin, because **JVM unit tests are the only tests this project
has**: no sibling project has an `androidTest/` directory and this one will not
be the first. Anything that needs the Android framework is glue, kept thin, and
verified on a device.

## Conventions

- **New screen** = a `feature/<x>/` package with a stateless `XScreen` composable
  plus a `@HiltViewModel XViewModel` exposing `StateFlow<XUiState>`. Add a
  `@Serializable` route in `navigation/Routes.kt` and a `composable<Route>` entry
  in `EddiesNavHost`.
- **New price source** = implement `PriceSource` in `data/price/`, add a
  `PriceSourceId`, and wire it into `PriceRepository`'s ladder.
- **Repositories** are `@Singleton` with constructor injection. A Hilt module
  exists only for framework or third-party types.
- **Persistence**: structured data to Room; settings to `SettingsDataStore`;
  secrets sealed by `SecretStore` first.
- **Never install the Ktor `Logging` plugin.** The request URLs carry the exact
  list of coins the user holds, which is the one thing this app exists to keep
  private.

## Invariants

**Money is never a Double.** Quantities and prices are `BigDecimal`, persisted as
TEXT via `Converters`. SQLite REAL is an IEEE double and cannot represent an
18-decimal token balance; the symptom would be a silently wrong net worth with
nothing in the logs. `PositionCalculatorTest` pins an 18-decimal round trip.

**Quantity is derived, never stored.** A position is folded from the ledger every
time, so correcting a typo cannot leave a stale balance behind. This is also what
makes staking separable: `STAKING_REWARD` rows carry `fromStaking` into the lot,
and `stakingQuantity` falls out of the fold rather than needing its own bookkeeping.

**Room migrations are explicit and never destructive.** Unlike a cache of
something a server owns, a transaction typed in by hand has no other copy.
`fallbackToDestructiveMigration` would destroy the only one.

**Asset ids are prefixed by class** (`crypto:btc-bitcoin`, `stock:NASDAQ:AAPL`).
That prefix is the entire reason Phase 3 equities can arrive as a new
`AssetClass` plus new source refs, with no migration of the ledger, the snapshots
or the price tables. META is both a token and a share; without the prefix they
merge.

**A stale price is marked, never shown as live.** `PriceTick.stale` drives a
visible badge. A three-hour-old number rendered as current is worse than no
number, because the user acts on it.

**Gain and loss are never carried by colour alone.** `PnlText` always pairs the
colour with a sign and a direction arrow. Red against green is invisible to
roughly one man in twelve.

**Sockets follow subscription, not lifecycle.** `PriceRepository` uses
`SharingStarted.WhileSubscribed(5_000)`, so a backgrounded app holds no
connection and there is no pause/resume code to get wrong. An empty portfolio
opens no socket at all.

**No key ships in the APK, and one permission is declared.** `INTERNET`, plus
network state and biometric. No storage permission: backups go through the
Storage Access Framework.

## Gotchas learned the hard way

- **sqlcipher-android is held at 4.9.0.** 4.18.0 declares `minCompileSdk=37`,
  which demands AGP 9.1 and would drag the toolchain, the container and CI up
  together. 4.9.0 declares `minCompileSdk=1` and its arm64 LOAD segments are
  `0x4000` aligned, so it is still correct for the 16 KB page size Android 15
  requires. Verify with `readelf -lW libsqlcipher.so` before bumping.
- **SQLCipher is two thirds of the APK.** Four ABIs at ~5 MB each. `splits.abi`
  brings arm64-v8a down to ~15.8 MB against a 30 MB universal build; both are
  published because this app is sideloaded.
- **`System.loadLibrary("sqlcipher")` runs before the factory is constructed.**
  Lazily inside it throws `UnsatisfiedLinkError` on the first query instead of at
  startup, where the cause is obvious.
- **MainActivity extends `FragmentActivity`, not `ComponentActivity`.**
  `BiometricPrompt` requires one. `LockScreen` fails closed with a message when
  it cannot find one, rather than silently unlocking.
- **Icons.\* cannot be import-aliased.** They are extension properties on a
  receiver, so `import ...ArrowBack as AutoArrowBack` does not resolve; write
  `Icons.AutoMirrored.Filled.ArrowBack`.
- **`PaddingValues` has no `(horizontal, bottom)` overload.** Only
  `(horizontal, vertical)` or the four-sided form.
- **The FX historical table is a Flow, not a suspend call.** It used to be read
  inside the portfolio `combine`, which meant a database read per price tick,
  about once a second per asset.
- **A reciprocal FX rate cannot round-trip exactly.** 1/1.10 repeats, so
  `110 USD -> EUR -> USD` lands on `110.000...001` at 34 digits. Full precision
  is kept through the calculation and rounding happens once, at display;
  assertions compare at a monetary scale.
- **Material 3 Expressive APIs are `internal`** in the pinned Compose BOM. Use
  standard `MaterialTheme`.
- **There is one app-level `Scaffold`**, in `EddiesNavHost`, with
  `contentWindowInsets = WindowInsets(0)`. A feature screen with its own Scaffold
  doubles the top inset, which looks like a design choice rather than a bug.
- **Hilt's Gradle plugin is flaky with the configuration cache.** Disabled in
  `gradle.properties`; do not turn it back on.
- **Live prices are upserted, never appended.** `price_latest` holds one row per
  asset. The v1 schema had a row per tick, and Kraken's ticker fires on every
  trade, so a single liquid pair was tens of thousands of rows a day and a
  database write per tick on a phone. Chart series live in `price_candles`.
- **`set -o pipefail` plus `head` aborts silently.** `scripts/refresh-icons.sh`
  materialises before taking the head, because closing the pipe early SIGPIPEs
  the upstream stages and kills the script mid-run with no error.

### Expected lint noise

`make lint` reports 0 errors and 38 warnings, all of them known. 37 are
`GradleDependency` / `NewerVersionAvailable` / `AndroidGradlePluginVersion`
pointing at the AGP 9 upgrade declined above. The last is `ObsoleteSdkInt` on
`res/mipmap-anydpi-v26`: lint suggests merging it into `mipmap-anydpi` since
minSdk is 35, but AAPT does not index that folder and the build fails with
*"resource mipmap/ic_launcher not found"*. The `-v26` qualifier stays.

A new warning outside that set is a real finding. The ones already fixed and
worth not reintroducing: `String.format` without an explicit `Locale.US` in chart
axis labels (it would disagree with MoneyFormat on the same screen), and
`roundIcon` pointing at `ic_launcher` rather than `ic_launcher_round`.

## Coin icons

`scripts/refresh-icons.sh` regenerates `assets/coins/` and `assets/asset_seed.json`.
Committed, so a fresh clone builds offline.

Two sources, because neither alone is enough. spothq (CC0) is keyed by ticker but
stale: measured at 17 of the top 25 and 32 of the top 100. ErikThiart (MIT) is
keyed by slugified coin name and current. Together they cover 21 of the top 25,
73 of the top 100, and 345 of the top 600. The rest get a generated monogram
tile, which is the normal appearance for the long tail, not a placeholder.

Both licences permit redistribution inside the APK. That is the whole reason
they can be bundled rather than fetched, and fetching per coin would tell a CDN
exactly what someone holds.

**Known follow-up:** icons ship as PNG (3.3 MB) because `cwebp` was not available
where the script last ran. It emits WebP when the encoder is present, which would
cut that to roughly 1.1 MB. Re-run on a machine with `cwebp` installed.

## Milestones

1. **Done.** Scaffold, container, GitHub CI, docs. Ledger schema and cost basis
   engine. Kraken and Binance sockets, aggregator fallback, FX. Portfolio,
   Insights, Markets, Settings, asset detail, add/edit transaction. Encrypted
   backup, CSV import and export. App lock. Dark by default. Advanced trader mode.

2. **Done. Price history.** `HistorySource` with Kraken, Binance and CoinPaprika
   implementations, all keyless. Range-selectable price chart on asset detail
   (1D hourly, everything else daily), portfolio backfill, watchlist.

   **Fetching is lazy and cached.** Opening a coin fetches that coin and nothing
   else; the several hundred in the seed are never prefetched. A refresh sends
   the newest cached timestamp as a delta hint, which against Kraken is about
   640 bytes instead of the 61 KB a full pull costs. One in-flight request per
   (asset, interval) stops the chart and the backfill duplicating work.

   **The backfill is the exception, and has to be.** A portfolio total is a sum
   across every held asset, so history for only the coins someone happened to
   open would produce a number that is quietly missing the rest. It covers held
   assets only, typically a handful, and shares the same cache.

   Days before an asset's history begins value it at zero rather than carrying
   the oldest known price backwards, which would draw a flat line that never
   happened.

3. **Next.** Staking. `StakingProvider` with `CardanoKoiosProvider` first; the
   user supplies a stake address per account and rewards import as
   `STAKING_REWARD` rows deduplicated on `(source, externalId)`. Portfolio totals
   already include them; asset detail already renders the principal-versus-
   rewards split. Add a rewards-over-time chart.

4. Stocks, and a combined dashboard. New `AssetClass.STOCK` plus new
   `PriceSource` and `HistorySource` implementations. The core tables do not
   change. Open question deferred to then: which equity data provider, since free
   realtime equity data is materially harder to source than crypto.

**Present a short plan before starting 3 or 4.** Milestones get agreed before
they get built, not after.

## Verified in the sandbox, 2026-08-25

`:app:testDebugUnitTest` (96 tests) passes. `:app:assembleDebug` (98 MB,
unminified) and `:app:assembleRelease` pass; the release splits are 15.8 MB
(arm64-v8a), 14.1 MB (armeabi-v7a), 16.3 MB (x86_64) and 30 MB (universal). The
release APK was unzipped and checked to contain 389 coin icons, `asset_seed.json`
and `libsqlcipher.so` for every ABI.

The Kraken, Binance, CoinPaprika and Frankfurter contracts above were checked
against the live endpoints, including holding a real WebSocket open.

What is NOT verified here, by construction: there is no phone, so nothing about
SQLCipher opening a database, the biometric prompt, socket behaviour under real
backgrounding, or how the charts feel has been observed. That is the user's to
run.
