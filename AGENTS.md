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
make test    # :app:testFullDebugUnitTest, the fast gate. Run this first
make build   # debug APK for the real app -> build-output/eddies-full-*.apk
make demo    # demo APK for screenshots, installs alongside the real app
make lint
make release # release APKs (signed if keystore.properties/env present)
make shell   # container shell for one-off gradle tasks
```

After changing a migration, also run `scripts/verify-migrations.sh`. After
changing anything the duplicate merge runs, `scripts/verify-merge.sh`. Both exist
for the same reason: SQL in a `@Query` or an `execSQL` is a string, so the
compiler cannot check it and the JVM suite cannot execute it. That is the part of
this app that ships unverified unless you run those. Both need a sqlite-jdbc jar
in `SQLITE_JAR`.

Definition of done: `make test` passes, and for anything touching UI or the
build, `make build` produces an APK. **Check the timestamp it prints.** Never
infer a successful build from a successful `make test`.

`make build` is the target; `app` and `debug` are aliases. That is not cosmetic:
`app/` and `build/` are real directories, so without the `.PHONY` declarations
`make app` decides it is up to date, prints *"Nothing to be done"* and exits 0.
`docker/run-gradle.sh` therefore ends by listing every APK in `build-output/` as
`fresh` or `STALE`, and a mistyped goal exits non-zero.

### Release signing

Optional. With none of it configured the release builds unsigned rather than
failing, which is deliberate: a fork should be able to build a release without
inventing a keystore.

CI reads four repository secrets: `RELEASE_KEYSTORE_BASE64` (the keystore,
base64 encoded), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
`RELEASE_KEY_PASSWORD`. Locally a git-ignored `keystore.properties` at the repo
root does the same job with `storeFile`, `storePassword`, `keyAlias` and
`keyPassword`.

`keystore/debug.keystore` **is** committed, and that is fine: it is the standard
Android debug key with the universally known `android` password. It is committed
so every debug build signs identically and reinstalling never hits "signatures do
not match".

CI is **GitHub Actions**, not Gitea. This is the first project here on GitHub, so
the workflows were ported rather than copied, and three differences are
load-bearing: `actions/upload-artifact` must be `@v4` (v3 was shut down in
January 2025 and hard-fails), the release job needs an explicit
`permissions: contents: write`, and releases are published with `gh release
create` rather than the Gitea API curl dance.

## Demo mode is a flavour, not a setting

`make demo` builds a separate installable app, `com.eddies.app.demo`, with a
fabricated portfolio for screenshots. It shares every line of app code with the
real build.

**Why not a runtime toggle.** Six places write to the database and two of them
run outside any screen: `RootViewModel` on every launch and `DailyWorker` on a
schedule. A toggle would have to be honoured by all six, forever, including in
code not yet written, and `DailyWorker` can fire mid-screenshot. A different
applicationId means a different data directory, so the demo build cannot reach
the real ledger at all. That isolation is enforced by the operating system rather
than by us remembering.

**No conditionals anywhere.** Anything that differs is an interface in `main`
with one implementation in `src/full` and another in `src/demo`, bound by a Hilt
module in each source set. Nothing in the app ever asks whether it is in demo
mode. There are two so far:

- `DemoSeeder`, which writes the fake portfolio or does nothing.
- `WindowSecurityPolicy`, which decides FLAG_SECURE. **The demo build never sets
  it**, whatever the "hide from recent apps" setting says. FLAG_SECURE blocks
  screenshots outright, not just the recents thumbnail, which makes the demo
  useless for its only purpose, and there are no real holdings in it to protect.
  Doing this by defaulting the setting off in the seeder would not hold: the
  seeder runs once on first launch, so an existing install would stay locked out,
  and the setting could be switched back at any time.

`scripts/verify-flavours.sh` proves the separation by extracting both APKs and
checking that each carries only its own implementations. The claim is not that
demo code is unreachable in a release, it is that it is not in the APK at all,
and CI runs this on every push.

That script exists because the check was first done by hand and was worthless:
a relative APK path after a `cd`, with stderr sent to `/dev/null`, so the
extraction failed, the grep searched files that did not exist, everything came
back "absent", and that was reported as verification. It happened to be true. It
had not been shown. The script asserts a control class is present precisely so a
broken extraction cannot pass as a clean result.

**Only the transactions are invented.** Prices, staking, Yahoo history, splits
and cost basis all run through the real code against the real feeds, which is
what makes the screenshots honest. The demo Cardano stake address is a real
public one, so the staking figure is fetched through the real Koios path.

**If you ever swap that address, pick one that has never withdrawn** and check
`withdrawals` on `/account_info` first. Pending rewards are earned minus
withdrawn, so an address whose owner cashes out reports zero from then on, and
the staking screenshot shows nothing where the whole point was a number. The
original choice here did exactly that, silently, some time after it was picked.
An account that has never withdrawn only accumulates.

Consequence worth knowing: totals move between screenshot sessions, because the
prices are live. If a set of shots ever needs to match exactly, that is the
argument for seeding price history too, and it is not built.

`app/src/demo/.../DemoPortfolio.kt` is the data. It deliberately includes a
realised loss, several custody locations, dividends, staking and a Tradegate
holding, so every screen has something to show. Screenshots that are uniformly
green read as marketing rather than as a tool.

**CI builds the demo flavour on every push** so it cannot rot unnoticed, and the
release workflow builds `assembleFullRelease` explicitly. A release must never
publish a demo APK: it carries a fabricated portfolio and would look like real
data.

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

### Yahoo Finance, for shares. Unofficial, and the user agent is load-bearing

`query1.finance.yahoo.com/v8/finance/chart/<symbol>`, keyless. It returns each
listing's own currency, its exchange, historical bars, and the split and
dividend events, which is everything the stock side needs from one host.

It is an **unofficial** endpoint and can change without notice. That is why
Settings offers an optional Finnhub key as an alternative quote source, and why
every parse here fails soft.

**Send a desktop user agent.** Verified 2026-08-26:

- no user agent returns HTTP 429
- a **mobile** user agent (anything advertising Android or Mobile) returns
  **HTTP 200 with the body "Too Many Requests"**

That second one is the trap. A success status carrying no JSON means every parse
silently yields nothing, so the stock side looks empty rather than broken and no
error is logged anywhere. `YahooApi.looksLikeJson` rejects a non-JSON 200 for
exactly this reason, and the user agent is a desktop string on purpose.

Splits arrive as `{"date":...,"numerator":4.0,"denominator":1.0}` keyed by
timestamp, in the same response as the bars, so a chart and a share count can
never disagree about which splits are known. Nulls in the `close` array are
non-trading gaps, not zeros; charting them as zero would draw a cliff to the axis
on every market holiday.

Search returns the exchange, which matters: `ASML` on NASDAQ and `ASML.AS` in
Amsterdam are different instruments at different prices in different currencies.

**There is no free realtime equity feed and there will not be.** Exchanges
license that data. Everything free is delayed or end of day, so the stock source
is a poller, quotes carry `stale` when the market is shut, and polling backs off
to fifteen minutes while every held market is closed.

### Tradegate, for instruments held on it

`www.tradegatebsx.com/refresh.php?isin=<ISIN>`, keyless, JSON, quoted in euros.
(`www.tradegate.de` 301s to that host; use the final one so nothing depends on
following a cross-host redirect.)

Tradegate exists here because **Yahoo does not cover it**. Every other German
venue has a suffix (`.DE` XETRA, `.F` Frankfurt, `.SG` Stuttgart, `.MU`, `.DU`,
`.HM`, `.HA`), but `.TG` returns nothing, so a position genuinely held on
Tradegate cannot be priced through the normal stock path.

**The response mixes number types, and this is the trap.** The same field is a
JSON number for one instrument and a German comma-decimal string for another,
apparently whenever the value would end in a trailing zero:

    SAP    "last":180.38       a number
    ASML   "last":"1501,60"    a string, comma decimal

Measured across four instruments, two came back as comma-strings, and it is not
confined to high and low: bid, ask and last all do it. `BigDecimal("1501,60")`
throws, the tick is dropped, and the holding shows **no price at all**, silently
and only for some instruments. Every value therefore goes through
`GermanNumber.parse`, which also strips a thousands separator: reading the dot in
"1.501,60" as a decimal point understates a holding a thousandfold.

An unlisted ISIN returns an **empty body**, not an error object.

The payload carries `refresh`, Tradegate's own suggested cadence in seconds (10
at the time of writing). `TradegateSource` honours it rather than guessing.

**No history.** It is a live snapshot only, so charts for a Tradegate holding
come from the same instrument's Yahoo listing, resolved by ISIN when the asset is
added and stored in `asset_source_refs`. Yahoo returns exactly one listing per
ISIN, so there is no choosing a euro-denominated one: `IE00B4L5Y983` resolves to
`IWDA.L` in London. The chart is converted to the base currency but is a
different venue's prints, and the asset detail screen says so rather than letting
the chart quietly disagree with the live price above it.

**An ISIN already held always resolves to that same holding.** `resolveTradegate`
looks the ISIN up in `asset_source_refs` before doing anything else and reuses
whatever asset carries it, keeping that asset's existing name and symbol. This is
not an optimisation, it is the identity rule: Yahoo's search returns several rows
for some ISINs (a real listing plus a synthetic `<ISIN>.SG` one) in an order
nothing guarantees, so deriving the id from whatever came back first would mint a
second holding for an ISIN already in the portfolio. Measured: `IE00BK5BQT80` and
`IE00BFY0GT14` both return two rows; an ISIN Yahoo does not index returns none,
so there is at least no fuzzy fallback to an unrelated fund.

**A Tradegate holding takes the id of the listing its ISIN resolves to**, so
`IE00B4L5Y983` added through the Tradegate tab is `stock:LONDON:IWDA.L`, the same
id the stock search would produce, with a `TRADEGATE` row in `asset_source_refs`
carrying the ISIN. Only when Yahoo cannot resolve the ISIN does it fall back to
`stock:TRADEGATE:<ISIN>`.

It did not start that way. Ids were minted per venue on the reasoning that a
venue with its own prices is its own listing, and it is wrong: one instrument
bought partly through the Tradegate tab and partly through the stock search
became two holdings, so the position, cost basis and allocation were all wrong
while every screen looked fine. See "Merging duplicate holdings" below for the
repair path for ledgers written before the change.

The consequence for anything reading the id: **the id no longer says where the
price comes from.** Route on a `TRADEGATE` source ref, never on
`AssetIds.exchangeOf(id) == "TRADEGATE"`. `PriceRepository` and the asset detail
chart caveat both do this.

Tradegate still gets its own search tab: it has no tickers, so a name query could
never work there.

### Koios, for Cardano staking

`api.koios.rest`, public tier keyless at 5000 requests a day. A sync is one
request per staking address.

**`account_info`, not `account_rewards`.** The rewards endpoint returns a row per
epoch back to 2020, which is hundreds of rows describing money that has mostly
been withdrawn already. `POST /account_info` with
`{"_stake_addresses":["stake1..."]}` gives the number that matters:

- `rewards_available` is earned minus withdrawn, so it is exactly what is still
  outstanding and can be added to a holding without double counting anything
  already spent or recorded by hand
- `rewards` is lifetime earned, informational
- `total_balance` is the real on-chain balance, currently unused but the obvious
  basis for a future "your recorded transactions say X, the chain says Y" check

Everything is lovelace as a string, so nothing goes through a Double and the
divide by 1,000,000 is exact. Verified against the live endpoint 2026-08-26.

Two facts recorded in case the per-epoch endpoint is ever wanted. Epoch start is
a pure function, `1596059091 + (epoch - 208) * 432000`, checked exact against
epochs 208, 300, 500 and 550, so no `epoch_info` call is needed. And real
accounts return reward types beyond staking: `leader` and `member` are income,
but `reserves`, `treasury` and `refund` are one-off protocol payouts and a
returned deposit, and counting those as rewards would overstate what was earned.

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
               transactions/ accounts/ settings/ backup/ about/ lock/ merge/
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

## Settings is a hub, and every preference has exactly one home

`SettingsScreen` is a list of seven categories, each its own route and screen:
General, Portfolio, Crypto, Stocks, Privacy and security, Data management,
About. It was one flat scroll of cards until shares arrived, at which point two
feeds, two fallback sources and two API keys sat interleaved in a single "Market
data" card with nothing saying which belonged to which.

**Split by subject, not by control type.** A stock preference goes in Stocks
even when Stocks holds one control, because the value of a hub is that there is
exactly one place a thing can be. Grouping by widget kind (all the switches, all
the choices) is the failure mode that produced the old screen.

Shared plumbing lives in `SettingsRows.kt`: `SettingsPage` is the scroll and
padding shell every category uses, and `ApiKeyEditor` is the one key form, since
the CoinGecko and Finnhub copies had already started to drift apart in wording.

**A preference that nothing reads is a bug, not a placeholder.** The
reorganisation turned up two: `secondaryCurrency` was displayed under the
portfolio total and restorable from a backup but had no control, so the only way
to change it was restoring a backup that carried a different one. It has a
control now. `autoLockSeconds` was stored, settable and read by nothing at all,
with `RootViewModel` re-locking immediately on backgrounding by design; it has
been removed rather than given a control that would do nothing. Exposing it
would have been worse than leaving it hidden.

## Invariants

**The lock screen is drawn over the nav host, never instead of it.** Returning
early from `EddiesNavHost` unmounted the whole thing, taking
`rememberNavController` with it, so unlocking built a fresh controller starting
at the portfolio. Every screen timeout threw away where the user was and anything
half typed. An opaque `Surface` over the top hides the content just as
completely.

**A half typed transaction survives the process being killed.**
`AddTransactionViewModel` mirrors every field into `SavedStateHandle` and
restores it after loading, so a screen that times out or a call that interrupts
does not lose the entry. A ViewModel alone is not enough: it dies with the
process, and Android kills backgrounded apps freely.

**A transaction is converted at the rate in force on its own date**, not today's.
`HistoricalRates.onOrBefore` is pure and tested, and returns null rather than the
oldest rate held when a transaction predates everything known. It used to fall
back, which valued a 2024 purchase at a 2026 rate: silently, plausibly, wrongly.
`FxRepository.ensureHistoryFrom` backfills the whole ledger range in one
Frankfurter call at startup, so the null case should not arise in practice.

**A default UI state must be distinguishable from real data.** Every screen
carries a `loaded` flag that only the real transform sets, and renders
`LoadingPlaceholder` until then. Without it the default `PortfolioUiState` is an
empty portfolio, indistinguishable from a genuinely empty one, so a populated
database rendered "nothing here yet" and a net worth of zero for a frame on every
cold start. The splash is dismissed as soon as settings load, which is well
before the database has answered, so that window is real and visible.

`LoadingPlaceholder` shows nothing at all for the first 400 ms on purpose. A
spinner that appears and vanishes inside a few hundred milliseconds is its own
flicker; only a genuinely slow load earns feedback.

**Animations are keyed on identity, never on values.** The allocation ring's
reveal is keyed on which holdings are present, not on their sizes. Keying it on
the sizes meant every price tick built a fresh `Animatable` at zero and restarted
the sweep, so against a feed that ticks several times a second the ring never
finished revealing: it sat there collapsing and re-expanding forever. Values
glide separately with `animateFloatAsState`. The same trap waits in any chart
whose data changes while it is on screen.

**The price feed is sampled before it reaches the portfolio.** Kraken's ticker
fires on every trade, and each emission would otherwise refold the whole ledger,
recompose the holdings list and re-sort it. One second still reads as
unmistakably live. The cost is that cached prices on a cold start appear up to a
second later, which is invisible next to opening the encrypted database.

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

**One instrument is one asset, whatever venue it was added through.** The id
comes from the instrument, and where its price comes from lives in
`asset_source_refs`: one asset can carry a Tradegate ISIN for live prices and a
Yahoo symbol for history at the same time. That table has existed since v1 for
exactly this and went unused until Tradegate needed it.

This replaced the opposite rule, "a venue with its own prices is its own
listing", which was defensible in the abstract and wrong in practice. The same
ETF reached through the Tradegate tab and through the stock search became two
holdings: one with one purchase and one with two, instead of one with three.
Nothing looked broken. The position, the cost basis and the allocation ring were
simply all wrong. Prices at two venues do differ slightly; being off by a spread
on one holding is worth far less than splitting a position in half.

**Merging duplicate holdings is user-initiated, and never a migration.**
Settings > Data > Merge duplicate holdings finds them (`DuplicateFinder`, pure
and tested) and `AssetMergeRepository` folds one into the other in a single
`withTransaction`. A migration was the obvious move and is the wrong one: the
match is a heuristic over the user's own data, and getting it wrong welds two
real positions together with no undo and no prompt. So the screen counts every
row that would move, names the ids, and waits.

**The ISIN outranks every other signal, in both directions.** Two holdings with
different ISINs are never grouped, whatever their tickers say, and two holdings
with the same ISIN are always grouped, whatever their tickers say. Only where no
ISIN is known does matching fall back to asset class plus ticker. Never on name:
two share classes of one fund read almost identically.

That guard is not theoretical. A single fund family can list many funds that
share a ticker on a broker statement while being entirely different products, so
a ticker-only rule was one tap away from welding two real positions into one. The
merge screen shows each entry's ISIN for the same reason: the suggestion has to
be checkable, not merely trustworthy. Within a group, the asset with the most
transactions is kept, so the fewest rows move.

`scripts/verify-merge.sh` seeds the real duplicate into a real SQLite database
built from the exported schema and replays the merge, with the SQL extracted from
`Daos.kt` and **the order extracted from `AssetMergeRepository.kt`**, because the
order is half the correctness. It found two defects on its first run:

- `asset_source_refs` cascade-deletes with its asset, so moving the Tradegate
  routing after the asset delete loses it and the holding silently stops being
  priced from the venue it is held at.
- `UPDATE OR IGNORE` leaves a row behind when the target already has an
  equivalent one, and every other table involved has no foreign key to cascade it
  away. A custody entry outlived the asset it described. Each reassign is now
  followed by a clear.

**Migration SQL is verified by executing it, not by reading it.**
`scripts/verify-migrations.sh` extracts every `execSQL` string **from the source
file**, replays the chain against real SQLite, seeds a row so `INSERT ... SELECT`
is actually exercised, and diffs the result against a fresh database built from
the exported schema. Run it after touching any migration.

This exists because a previous check hand copied the statements into the test.
It verified the intent and passed, while the shipped app crashed on launch with
`no such column: CRYPTO`: a quote had been lost from `CRYPTO` in the source and
the copy in the test still had it. A check that duplicates the thing it is
checking is not a check.

**Splits are replayed, never written into the ledger.** `corporate_actions`
holds the events and `PositionCalculator` applies them while walking the
timeline. A split multiplies quantity and divides unit cost, so total basis is
invariant. Ignoring them makes a position look like it lost three quarters of its
value overnight; rewriting the ledger for them destroys what the user typed.

**Staking is a balance, not ledger rows.** `staking_balances` holds one live
figure per stake address, replaced on each sync. Rewards accrue continuously and
are withdrawn in lumps, so per-epoch transactions would go stale the moment
anything is withdrawn and would double count against a withdrawal entered by
hand. They carry no cost basis and are valued at today's price, so they read
wholly as gain, which is correct: they were never paid for.

**Custody lives in its own table, and must.** `asset_custody` records where a
coin is actually kept. It is deliberately not columns on `AssetEntity`, because
those rows are re-upserted wholesale whenever the bundled seed version changes
and anything the user typed there would be silently erased by a routine icon
refresh. It is equally deliberately independent of the ledger: an account records
where a transaction happened, custody records where the coins live now, and the
two diverge the moment you buy on an exchange and move to a wallet. Keeping them
apart is what makes recording custody cost no transfer bookkeeping. One row per
asset; a coin genuinely split across two places goes in the note rather than
being modelled.

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

On the `floating-nav-pill` branch it is 39, the extra one being
`GradleDependency` saying material3 alpha27 is newer than the alpha18 pinned
there. That pin is deliberate; see the version catalog comment.

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

### Kotlin compiler warnings are zero, and should stay zero

Unlike lint, the Kotlin build is silent. Keep it that way: it is the only reason
a new warning is worth reading. Ten deprecation warnings were tolerated for a
while and were hiding a real one, a missing `@OptIn(FlowPreview::class)` on the
`sample()` that throttles the price feed, which nobody noticed until the noise
was cleared.

Two things keep it silent and are easy to undo by accident:

- `hiltViewModel` comes from `androidx.hilt.lifecycle.viewmodel.compose`, not
  `androidx.hilt.navigation.compose`. androidx.hilt 1.3.0 moved it and
  deprecated the old copy, and `hilt-navigation-compose` is not a dependency at
  all so the deprecated import cannot come back by autocomplete.
- Hilt qualifiers on constructor parameters are written `@param:ApplicationContext`.
  Kotlin 2.2 warns that a bare annotation will start applying to the field too.
  The explicit target pins current behaviour; the generated
  `*_Factory` still carries
  `@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")`,
  which is how to check it is still wired rather than merely compiling.

Icons use the `AutoMirrored` variants where one exists. The app declares
`supportsRtl="true"`, so a directional icon that does not flip is a real defect
in a right-to-left locale, not a style preference.

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

3. **Done. Stored at.** `asset_custody` plus `CustodyGrouper`. A per-asset
   location with a closed set of kinds (hardware wallet, exchange, software
   wallet, cold storage, DeFi, other) for the icon and the grouping, and a free
   text name because no curated exchange list would ever match a real setup.
   Names already used appear as chips, which is what stops the set fragmenting
   into "Kraken", "kraken" and "Kraken exchange". Editable from asset detail,
   summarised in Insights under "Where it is kept", and carried in the encrypted
   backup, because the moment you most need to know which wallet holds your BTC
   is while setting the app up on a new phone.

4. **Done. Staking.** `StakingProvider` with `CardanoKoiosProvider` first, plus
   `staking_balances`. A stake address on an account produces a live outstanding
   figure that adds to the holding and to the portfolio total.

   **Modelled as a balance, not as ledger rows.** Rewards accrue continuously and
   are withdrawn in lumps, so a transaction per epoch would be hundreds of rows
   that go stale the moment anything is withdrawn, and would double count against
   a withdrawal entered by hand. `rewards_available` is earned minus withdrawn,
   which is precisely the amount that is still outstanding.

   **Valued at today's price, never historically.** The question is "how much of
   my ADA did I earn", not "what was my income in 2021". That also sidesteps the
   fact that price history reaches back about two years while Cardano staking
   goes back to 2020.

   Rewards therefore carry no cost basis, so they show up wholly as gain, which
   is correct: they were never paid for. `PortfolioBuilder` keys off the union of
   ledger assets and staked assets, because a coin held only as accrued rewards
   is still a real position and keying off the ledger alone would hide it.

   A failed sync keeps the last known figure and stores the reason, so the UI can
   say when it was last checked instead of showing a stale number as current.

5. **Done. Stocks, and the combined view.** `AssetClass.STOCK` with Yahoo as the
   keyless default and an optional Finnhub key. The core tables did not change,
   which was the point of prefixing asset ids by class: shares slot into the same
   ledger, the same price pipeline, the same custody table and the same backup.

   **Splits are replayed, never written back.** A 4:1 split multiplies the share
   count and divides the unit cost, leaving total basis untouched. They are
   interleaved with transactions chronologically rather than applied at the end,
   because a sale entered before a split is denominated in pre-split shares and
   applying the ratio afterwards would leave the wrong number on both sides.
   Rewriting the ledger instead would destroy what the user typed and would be
   unrecoverable if the provider later corrected the ratio.

   **Dividends are the stock-side twin of staking rewards.** Both are earned
   rather than bought, so `Holding.incomeValue` adds them into one figure and the
   combined view presents "earned" once instead of making the user hold two ideas.
   A dividend carries `cashAmount` and moves neither quantity nor basis, which is
   why it needed its own accumulator rather than being squeezed into an existing
   one.

   Class routing runs through the price pipeline: a crypto exchange has never
   heard of AAPL and Yahoo has no opinion on the long tail of tokens, so each
   class goes to its own ladder. `PortfolioScope` filters the portfolio to All,
   Crypto or Stocks, and per-class snapshots make each chartable. The parts are
   derived from the same holdings as the whole, so a per-class total and the
   grand total can never disagree.

6. **Done. Tradegate.** A German venue Yahoo does not carry, added as its own
   price source keyed by ISIN. Live prices from Tradegate, history and splits
   from the same instrument's Yahoo listing via `asset_source_refs`. ISINs are
   check-digit validated locally so a typo is caught before a request goes out
   and the message can say "typo" rather than "not found".

7. **Done. Demo flavour.** A separate installable app with a fabricated
   portfolio, for store and release screenshots, sharing all app code. Isolation
   is structural rather than conditional: see the section above for why a
   runtime toggle was rejected.

8. **Done. Loose ends.** Three things the app half-promised. The secondary
   currency setting was stored, backed up and settable but never displayed
   anywhere; it now sits under the portfolio total, and only when one is set and
   differs from the main one. Transactions were reachable only per asset, so the
   ledger as a whole could not be read; there is now an all-transactions screen,
   which is also the only place a fully sold position is visible, since it leaves
   the portfolio but its realised profit stays in the totals. And `onboarded` was
   written and read but nothing ever branched on it; it now separates "never
   started" from "sold everything", which want different empty states.

9. **Done. One instrument, one holding.** The same ETF added through the
   Tradegate tab and through the stock search was two holdings. Fixed at the
   root, so it cannot happen again, plus a merge screen to repair ledgers already
   written that way, plus `verify-merge.sh` to prove the merge does not lose
   anything. Asset detail also grew an "+ Add" button on its transactions
   section: adding a second purchase of something already held meant searching
   for it again from scratch.

10. **Parked.** Dividend reinvestment (DRIP), where a dividend buys shares rather
   than paying cash. It needs per-position reinvestment settings and
   reconciliation against what the broker actually did, and the ledger already
   represents it as a DIVIDEND plus a BUY for anyone who wants it today.

   Also parked, in rough order of value if picked up: a home screen widget
   (glanceable net worth, the highest-frequency interaction a tracker has), a
   broker-aware CSV import that recognises DEGIRO, Trade Republic, Kraken and
   Bitvavo exports by their header row rather than making the user map columns,
   and a per-year 1 January valuation, which several European wealth-tax regimes
   ask for and which the daily snapshots can already answer exactly.

**Present a short plan before starting 10.** Milestones get agreed before they
get built, not after.

## Verified in the sandbox, 2026-08-26

`:app:testFullDebugUnitTest` (189 tests) passes, along with `lintFullDebug`
(0 errors), `assembleFullDebug`, `assembleDemoDebug` and `assembleFullRelease`.
Release splits are roughly 15 MB (arm64-v8a), 13.6 MB (armeabi-v7a), 15.7 MB
(x86_64) and 28.8 MB (universal). The release APK was unzipped and checked to
contain 389 coin icons, `asset_seed.json` and `libsqlcipher.so` for every ABI,
and every native library was confirmed 16 KB aligned.

`scripts/verify-migrations.sh` replays the whole migration chain against real
SQLite and matches a fresh database. `scripts/verify-merge.sh` merges a seeded
duplicate and confirms 3 buys across 2 entries become 3 buys on 1 with the
Tradegate routing intact. Both were checked to actually fail: reordering the
source-ref move after the asset delete turns the merge run red.

Every market-data contract above was checked against the live endpoints: Kraken
(including holding a real WebSocket open), Binance, CoinPaprika, Frankfurter,
Koios, Yahoo and Tradegate.

What is NOT verified here, by construction: there is no phone, so nothing about
SQLCipher opening a database, the biometric prompt, socket behaviour under real
backgrounding, or how the charts feel has been observed. That is the user's to
run.

## The floating navigation pill (branch: `floating-nav-pill`)

An experiment, deliberately isolated from master. The bottom `NavigationBar` is
replaced by `HorizontalFloatingToolbar`: a rounded pill that hovers over the
content instead of reserving a strip of it, with the selected tab showing its
label and the rest icon only.

**Why it needs an alpha.** `HorizontalFloatingToolbar` is a real Material 3
component but is not in material3 1.4.0 stable, which ships only
`FloatingToolbarTokens`. There is no stable 1.5.0. So the version catalog
overrides the Compose BOM for material3 alone.

**Why alpha18 and not the newest.** alpha19 raises `minCompileSdk` to 37 and
`minAndroidGradlePluginVersion` to 9.1.0. That is the identical wall that keeps
sqlcipher at 4.9.0, and taking it would drag AGP, the Docker image and CI along.
alpha18 is the last one that asks for compileSdk 35 and AGP 8.6.0, both of which
this project already exceeds. Found by probing each alpha's
`aar-metadata.properties` rather than by upgrading and seeing what broke.

It still pulls compose foundation and ui from 1.9.3 up to 1.11.0-beta02,
transitively, because the alpha requires it. Everything builds and all tests
pass, but that is a beta Compose runtime under the whole app, which is the real
reason this is a branch and not a commit on master.

**No bottomBar and no Scaffold FAB slot any more.** The pill is an overlay inside
the Scaffold content, aligned bottom-centre, applying
`WindowInsets.navigationBars` itself since it sits outside any inset-aware slot.
This works only because every tab screen already ends with 96.dp of bottom
padding, which is now load-bearing: a new tab screen that forgets it will have
its last row sitting under the pill.

**That overlay Box must be `fillMaxSize`.** A Box wraps its content, so
`Alignment.BottomCenter` means the bottom of whatever is currently measured. On a
cold navigation the incoming screen deliberately renders nothing for 400ms
(`LoadingPlaceholder`, so a populated database never flashes an empty state), the
Box collapses to roughly the pill's own height, and the pill draws near the top
of a black screen before being shoved back into place once content arrives. It
looks like a broken transition and it is a layout bug. The Scaffold used to own
that positioning through `bottomBar`; taking the pill out of that slot made it
the Box's job.

**The add button is a top bar action, not a FAB.** A tracker is read-mostly:
positions are added occasionally and looked at daily, so a permanently docked
button claimed more of the screen than the action earns, and it left two floating
objects competing at the bottom edge. It now sits beside the transactions icon on
the portfolio screen. Discovery is unaffected: the empty state still leads with a
full-width button, which is the only moment it matters.

Removing it also fixed a second problem. Pairing the FAB with the pill meant the
whole pill slid sideways whenever you left the portfolio tab, moving the target
out from under the finger that had just tapped it. With nothing else at the
bottom, the pill is centred once and stays there.

**The colours are deliberately not the Material defaults.** The stock checked
`ToggleButton` fills with solid `primary`, and this app's primary is a bright
cyan on a near-black background, so one glowing lozenge outshone the whole
screen. The selected tab now gets a 16% cyan wash with cyan content, and the
toolbar uses `standardFloatingToolbarColors` rather than the vibrant set.

**The pill is edged, not shadowed.** List rows and the stock toolbar both land
on `surfaceContainer`, so a card scrolling behind the pill merged into it. The
container moves one step to `surfaceContainerHigh` and the pill takes a 1.dp
`outlineVariant` rim.

The rim is there because **a drop shadow cannot work on this theme**. Android
shadows darken what is behind them, and darkening a 0xFF0B0E11 background
produces nothing. Every "raise the elevation" answer to a separation problem is
invisible on OLED, which is why well-made dark interfaces edge their floating
surfaces instead. `outlineVariant` rather than a white alpha because it is a mid
tone in both schemes, so one line lifts the edge in the dark theme and settles
it in the light one.

**Not verified here:** how any of it actually looks, and in particular whether
the 1.dp rim reads as crisp or as a drawn-on outline. That needs the phone, and
the rim is the one choice here most likely to want a nudge, either in weight or
in how far the container tone is lifted.

**The pill swallows every touch that lands on it.** A tap in its padding, or in a
gap between two items, hits no pointer input node inside the pill, so the hit
test carries on to the NavHost sibling underneath and opens whichever card is
scrolled beneath the bar. The finger was in the right place; the dead zone was.
It reads as a mistap on the navigation and it is one, just not the user's.

The fix is a `pointerInput` on the toolbar consuming on `PointerEventPass.Main`.
The pass matters: `Initial` travels parent to child and would eat taps before any
`ToggleButton` saw them; `Main` travels child to parent, so the buttons get first
refusal and the container only mops up what they left. A full-width `bottomBar`
never had this problem because nothing was behind it.

**Auto-hide on scroll is a setting, off by default.** Settings > General >
Appearance > "Hide navigation when scrolling", stored as `hideNavOnScroll` and
carried in backups with a default so older backup files still restore.

It uses Material's own `FloatingToolbarDefaults.exitAlwaysScrollBehavior`, not
anything hand-rolled. The behaviour *is* a `NestedScrollConnection`, so it is
attached with `Modifier.nestedScroll` on the Box wrapping the NavHost rather than
per screen: nested scroll propagates upward, so one ancestor covers every list.

**The offset is placed by hand, not passed to the toolbar.** Handing the
component its `scrollBehavior` parameter applies the offset inside its own
layout, which is inside everything in the modifier chain: the container slid
away on scroll and the border stayed put, drawing an empty outline where the
pill had been. `FloatingToolbarScrollBehavior.floatingScrollBehavior` is public
for exactly this. It goes above the border and the touch handling so both travel
with the pill. Anything else decorating the pill from the outside has to sit
below it too.

Off by default on purpose. Hiding an app bar is uncontroversial; hiding the only
way to change tabs is not, and the two positions are both reasonable. That is the
line for adding a setting at all in this app: a genuine disagreement about
behaviour earns one, differing taste in chrome does not. The same reasoning is
why there is no full-bar-versus-pill toggle. Two navigation layouts would have to
be maintained forever, each with its own padding contract, so that nobody has to
adapt to a bar in a day.
