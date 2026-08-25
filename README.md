# Eddies

A crypto portfolio tracker for Android that keeps your holdings on your phone.

No account. No server of its own. No analytics, no crash reporting, no ads. The
database is encrypted, the app declares one meaningful permission (internet), and
the only thing it ever sends anywhere is which coins to price.

Named after Cyberpunk 2077's eurodollars, which turned out to fit: EUR and USD
are the two currencies it ships with.

## What it does

- **Live prices.** A public WebSocket feed from Kraken or Binance, your choice,
  with a REST aggregator covering coins no exchange lists. Kraken is the default
  because it quotes EUR pairs directly, so no conversion is needed for most
  holdings.
- **Real cost basis.** Positions are derived from a transaction ledger rather
  than a stored balance, so you get unrealised and realised profit, average cost,
  and a correct answer after a partial sell. Average, FIFO, LIFO and HIFO.
- **Charts that say something.** Portfolio value over time with a draggable
  crosshair, allocation, movers. Drawn directly rather than by a chart library,
  so they follow your theme.
- **Simple by default.** Advanced trader mode adds cost basis, realised P/L,
  market cap ranks and per-row detail once you want them.
- **Encrypted backups.** A passphrase-protected file you write wherever you like.
  Plain CSV export too, for a spreadsheet or a tax tool, clearly labelled as
  unencrypted.
- **Dark by default**, with a true-black OLED mode.

Staking rewards and regular stocks are planned. The data model already
distinguishes rewards from purchases, so a staked holding will show what you
earned separately from what you bought.

## Installing

Grab an APK from the [releases page](../../releases). There are several:

| File | For |
|---|---|
| `eddies-<version>-arm64-v8a.apk` | Almost every phone made since 2017. Start here. |
| `eddies-<version>-armeabi-v7a.apk` | Older 32-bit devices. |
| `eddies-<version>-x86_64.apk` | Emulators. |
| `eddies-<version>-universal.apk` | Works everywhere, roughly twice the size. |

The per-architecture builds are about 15 MB against 30 MB for the universal one.
Most of that is the SQLCipher native library that encrypts the database.

## Privacy

The app talks to three kinds of service, and only about prices:

- an exchange (Kraken or Binance) for live prices
- CoinPaprika, or CoinGecko with your own key, for coins the exchange does not list
- Frankfurter, for European Central Bank currency rates

Those requests name the coins being priced. That is unavoidable for a price, but
it is why coin icons ship inside the app rather than being fetched, and why coin
search is offline unless you turn remote lookup on.

Your ledger is stored in a SQLCipher-encrypted database whose key is held in the
Android Keystore. System backups are disabled, because a restored backup would
carry a database the new device has no key for. Portable backups are the
passphrase-encrypted file you write yourself, which is deliberately independent
of the Keystore so it opens on a new phone.

## Building

Everything runs in a container. There is no need for a local JDK or Android SDK.

```
make test      # unit tests, the fast gate
make build     # debug APK  -> build-output/
make lint
make release   # release APKs, signed if you have a keystore configured
make shell     # a shell in the build container
```

Every build prints the APKs it produced with timestamps, and flags anything left
over from an earlier run as `STALE`. Check it rather than assuming.

## Releasing

Tag `v*` and push. GitHub Actions runs the tests as a gate, builds the release
APKs, and attaches them to a GitHub release.

Signing is optional. Set these repository secrets and the release is signed;
leave them unset and it builds unsigned:

| Secret | What |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | Your keystore, base64 encoded |
| `RELEASE_KEYSTORE_PASSWORD` | Store password |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

Locally, a git-ignored `keystore.properties` at the repo root does the same job:

```properties
storeFile=release.jks
storePassword=...
keyAlias=eddies
keyPassword=...
```

## Coin icons

`scripts/refresh-icons.sh` regenerates the bundled icon set and the offline coin
list. It pulls from two sources, both permissively licensed so they can ship
inside the APK: [ErikThiart/cryptocurrency-icons](https://github.com/ErikThiart/cryptocurrency-icons)
(MIT) and [spothq/cryptocurrency-icons](https://github.com/spothq/cryptocurrency-icons)
(CC0). Coins with no artwork in either get a generated monogram tile.

Install `cwebp` before running it to cut the bundled icons from about 3.3 MB to
1.1 MB.

## Licence

GPLv3. See [LICENSE](LICENSE).
