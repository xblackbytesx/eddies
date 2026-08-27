# Screenshots

All taken from the demo build (`make demo`), which installs alongside the real
app with a fabricated portfolio. The holdings and transactions are invented. The
prices, charts and staking figures are live, fetched through the same code the
real app uses.

| | |
|---|---|
| ![Portfolio, combined](01-home-screen-combined.png) | ![Portfolio, crypto only](02-home-screen-filter-crypto.png) |
| **Portfolio, everything** | **Portfolio, crypto only** |
| ![All transactions](03-all-transactions-screen.png) | ![Insights](04-insights-screen.png) |
| **The full ledger** | **Insights: allocation and profit** |
| ![Insights, scrolled](05-insights-screen-scrolled.png) | ![Markets](06-markets-screen.png) |
| **Insights: where it is kept** | **Markets, with search** |
| ![Asset detail](07-asset-detail-screen.png) | ![Add a transaction](08-add-transaction-screen.png) |
| **Asset detail** | **Adding a transaction** |
| ![Settings](09-settings-screen.png) | |
| **Settings** | |

## Replacing these

Build the demo app, take the shots, drop them in here with the same numbering.

```
make demo
```

The demo build deliberately does not set FLAG_SECURE, so screenshots work even
with "hide from recent apps" turned on. The real build does set it, which is why
these cannot be taken from an ordinary install.
