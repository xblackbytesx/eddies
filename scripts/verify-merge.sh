#!/usr/bin/env bash
# Runs the real duplicate-merge against a real SQLite database seeded with the
# duplicate this feature exists for, and checks nothing is lost.
#
# Why this exists: merging rewrites which asset a user's transactions belong to
# and has no undo. Every statement it runs is a @Query string, so the compiler
# checks none of it, and there is no instrumentation test suite here to run it.
#
# So, like verify-migrations.sh: the SQL is EXTRACTED FROM Daos.kt and the ORDER
# is EXTRACTED FROM AssetMergeRepository.kt. Never paste either here. The order
# is half the correctness: asset_source_refs cascade-delete with their asset, so
# moving the Tradegate price routing after the asset delete would lose it, and
# the merged holding would silently stop being priced from the venue it is held
# at. This check fails if that is ever reordered.
#
# Needs a local JDK and sqlite-jdbc. See AGENTS.md.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DAOS="$REPO_ROOT/app/src/main/java/com/eddies/app/data/db/dao/Daos.kt"
MERGE="$REPO_ROOT/app/src/main/java/com/eddies/app/data/repo/AssetMergeRepository.kt"
SCHEMAS="$REPO_ROOT/app/schemas/com.eddies.app.data.db.EddiesDatabase"

JAVA="${JAVA:-java}"
SQLITE_JAR="${SQLITE_JAR:?set SQLITE_JAR to a sqlite-jdbc jar}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/VerifyMerge.java" <<'JAVA'
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Seeds the duplicate, replays the merge as the repository would, asserts the result. */
public class VerifyMerge {

    /** dao field name in the repository -> its interface name in Daos.kt. */
    static final Map<String, String> FIELD_TO_DAO = new LinkedHashMap<>();
    /** "InterfaceName.methodName" -> the SQL of its @Query. */
    static final Map<String, String> QUERIES = new LinkedHashMap<>();

    static String unquote(String s, int from) {
        int q1 = s.indexOf('"', from);
        if (q1 < 0) return null;
        StringBuilder out = new StringBuilder();
        for (int k = q1 + 1; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (ch == '\\') { k++; if (k < s.length()) out.append(s.charAt(k)); continue; }
            if (ch == '"') return out.toString();
            out.append(ch);
        }
        return null;
    }

    /** Every @Query in Daos.kt, keyed by the interface it sits in. */
    static void parseDaos(Path daos) throws Exception {
        String iface = "?";
        String pendingSql = null;
        StringBuilder triple = null;
        for (String line : Files.readAllLines(daos)) {
            String t = line.trim();
            Matcher m = Pattern.compile("^interface (\\w+) \\{").matcher(t);
            if (m.find()) { iface = m.group(1); continue; }

            if (triple != null) {
                if (t.startsWith("\"\"\"")) {
                    pendingSql = triple.toString().trim();
                    triple = null;
                } else {
                    triple.append(' ').append(t);
                }
                continue;
            }
            if (t.startsWith("@Query(\"\"\"")) { triple = new StringBuilder(); continue; }
            if (t.startsWith("@Query(")) { pendingSql = unquote(t, 6); continue; }

            if (pendingSql != null && t.contains("fun ")) {
                Matcher f = Pattern.compile("fun (\\w+)\\(").matcher(t);
                if (f.find()) QUERIES.put(iface + "." + f.group(1), pendingSql.replaceAll("\\s+", " ").trim());
                pendingSql = null;
            }
        }
    }

    /** The repository's constructor, so a call on `custodyDao` resolves to CustodyDao. */
    static void parseFields(Path repo) throws Exception {
        Matcher m = Pattern.compile("private val (\\w+): (?:[\\w.]*\\.)?(\\w+Dao)[,)]")
            .matcher(Files.readString(repo));
        while (m.find()) FIELD_TO_DAO.put(m.group(1), m.group(2));
    }

    /** The calls inside merge(), in source order. This is the thing under test. */
    static List<String[]> parseMergeOrder(Path repo) throws Exception {
        String src = Files.readString(repo);
        int start = src.indexOf("db.withTransaction {");
        if (start < 0) throw new IllegalStateException("merge() no longer runs in a transaction");
        int end = src.indexOf("\n        }", start);
        String body = src.substring(start, end < 0 ? src.length() : end);

        List<String[]> calls = new ArrayList<>();
        Matcher m = Pattern.compile("(\\w+)\\.(\\w+)\\(from\\.id(, group\\.keep\\.id)?\\)").matcher(body);
        while (m.find()) {
            String dao = FIELD_TO_DAO.get(m.group(1));
            if (dao == null) throw new IllegalStateException("unknown dao field: " + m.group(1));
            String key = dao + "." + m.group(2);
            String sql = QUERIES.get(key);
            if (sql == null) throw new IllegalStateException("no @Query found for " + key);
            calls.add(new String[] { key, sql, m.group(3) == null ? "one" : "two" });
        }
        return calls;
    }

    static String extractString(String s, int keyPos) {
        int q1 = s.indexOf('"', keyPos + 12);
        boolean esc = false;
        for (int k = q1 + 1; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (esc) { esc = false; continue; }
            if (ch == '\\') { esc = true; continue; }
            if (ch == '"') return s.substring(q1 + 1, k).replace("\\\"", "\"");
        }
        return "";
    }

    /** Same reader as verify-migrations.sh: build the real tables from Room's export. */
    static List<String> createSql(Path schemaDir, int version) throws Exception {
        String s = Files.readString(schemaDir.resolve(version + ".json"));
        List<String> out = new ArrayList<>();
        Matcher tables = Pattern.compile("\"tableName\":\\s*\"([^\"]+)\"").matcher(s);
        List<String> names = new ArrayList<>();
        while (tables.find()) names.add(tables.group(1));
        int idx = 0;
        Matcher creates = Pattern.compile("\"createSql\"").matcher(s);
        while (creates.find()) {
            String sql = extractString(s, creates.start());
            if (sql.startsWith("CREATE TABLE")) {
                out.add(sql.replace("${TABLE_NAME}", names.get(Math.min(idx++, names.size() - 1))));
            } else if (sql.startsWith("CREATE INDEX")) {
                Matcher n = Pattern.compile("`(index_[^`]+)`").matcher(sql);
                if (n.find()) {
                    String rest = n.group(1).substring("index_".length());
                    String table = names.stream()
                        .filter(t -> rest.startsWith(t + "_"))
                        .max(Comparator.comparingInt(String::length)).orElse(null);
                    if (table != null) out.add(sql.replace("${TABLE_NAME}", table));
                }
            }
        }
        return out;
    }

    static final String KEEP = "stock:LONDON:IWDA.L";
    static final String GONE = "stock:TRADEGATE:IE00B4L5Y983";

    static int count(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    static boolean ok = true;

    static void check(boolean condition, String what) {
        System.out.println((condition ? "    ok   " : "    FAIL ") + what);
        if (!condition) ok = false;
    }

    public static void main(String[] a) throws Exception {
        Path schemaDir = Path.of(a[0]);
        Path daos = Path.of(a[1]);
        Path repo = Path.of(a[2]);
        Path work = Path.of(a[3]);
        int newest = Integer.parseInt(a[4]);

        parseDaos(daos);
        parseFields(repo);
        List<String[]> order = parseMergeOrder(repo);
        System.out.println("==> Merge runs " + order.size() + " statements, in this order:");
        for (String[] call : order) System.out.println("      " + call[0]);

        Path db = work.resolve("merge.db");
        Files.deleteIfExists(db);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                for (String sql : createSql(schemaDir, newest)) st.execute(sql);
            }
            System.out.println("==> Seeding the duplicate against v" + newest + " of the real schema");

            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO accounts (id, name, kind, createdAt) VALUES (1, 'Main', 'BROKER', 1)");
                for (String id : new String[] { KEEP, GONE }) {
                    st.execute("INSERT INTO assets (id, assetClass, symbol, name, decimals, tracked) VALUES ('"
                        + id + "', 'STOCK', 'IWDA.L', 'iShares Core MSCI World', 4, 1)");
                }
                // Two buys on the listing, one on the Tradegate entry: the user's
                // "one with 1 purchase and another with 2 instead of it being 3".
                String tx = "INSERT INTO transactions "
                    + "(accountId, assetId, type, quantity, pricePerUnit, quoteCurrency, timestamp, source) VALUES (1, ";
                st.execute(tx + "'" + KEEP + "', 'BUY', '10', '90.10', 'EUR', 1700000000000, 'MANUAL')");
                st.execute(tx + "'" + KEEP + "', 'BUY', '5', '95.00', 'EUR', 1710000000000, 'MANUAL')");
                st.execute(tx + "'" + GONE + "', 'BUY', '7', '101.55', 'EUR', 1720000000000, 'MANUAL')");

                // Custody on BOTH, which is the collision the OR IGNORE is for.
                st.execute("INSERT INTO asset_custody (assetId, type, label, updatedAt) VALUES ('"
                    + KEEP + "', 'EXCHANGE', 'DEGIRO', 1)");
                st.execute("INSERT INTO asset_custody (assetId, type, label, updatedAt) VALUES ('"
                    + GONE + "', 'EXCHANGE', 'Tradegate', 1)");

                st.execute("INSERT INTO watchlist (assetId, addedAt) VALUES ('" + GONE + "', 1)");
                st.execute("INSERT INTO corporate_actions "
                    + "(assetId, timestamp, numerator, denominator, fetchedAt) VALUES ('"
                    + GONE + "', 1715000000000, '2', '1', 1)");
                st.execute("INSERT INTO price_latest (assetId, timestamp, price, currency, source) VALUES ('"
                    + GONE + "', 1, '101.55', 'EUR', 'TRADEGATE')");
                st.execute("INSERT INTO price_candles "
                    + "(assetId, interval, timestamp, close, currency, source) VALUES ('"
                    + GONE + "', 'DAY', 1720000000000, '1', 'EUR', 'YAHOO')");

                // Staking is crypto-only in practice, but the merge moves it, so
                // a row proves the statement runs rather than silently not applying.
                st.execute("INSERT INTO staking_balances "
                    + "(stakeAddress, assetId, accountId, pending, totalEarned, syncedAt) VALUES "
                    + "('stake1test', '" + GONE + "', 1, '0', '0', 1)");

                // The routing that must survive: this is what keeps the merged
                // holding priced from Tradegate rather than falling back to Yahoo.
                st.execute("INSERT INTO asset_source_refs (assetId, source, sourceSymbol) VALUES ('"
                    + KEEP + "', 'YAHOO', 'IWDA.L')");
                st.execute("INSERT INTO asset_source_refs (assetId, source, sourceSymbol) VALUES ('"
                    + GONE + "', 'TRADEGATE', 'IE00B4L5Y983')");
            }

            int before = count(c, "SELECT COUNT(*) FROM transactions");
            System.out.println("    " + before + " transactions across 2 assets");

            System.out.println("==> Replaying the merge");
            c.setAutoCommit(false);
            for (String[] call : order) {
                // Bound before substitution: the asset ids contain colons too,
                // so an unbound-parameter check has to run on the raw query.
                String raw = call[1];
                Matcher unbound = Pattern.compile(":(\\w+)").matcher(raw);
                while (unbound.find()) {
                    String p = unbound.group(1);
                    if (!p.equals("toAssetId") && !p.equals("fromAssetId")
                        && !p.equals("assetId") && !p.equals("id")) {
                        System.out.println("  " + call[0] + " takes an argument this check does not "
                            + "know how to bind: :" + p);
                        System.exit(1);
                    }
                }
                String sql = raw
                    .replace(":toAssetId", "'" + KEEP + "'")
                    .replace(":fromAssetId", "'" + GONE + "'")
                    .replace(":assetId", "'" + GONE + "'")
                    .replace(":id", "'" + GONE + "'");
                try (Statement st = c.createStatement()) {
                    st.execute(sql);
                } catch (SQLException e) {
                    System.out.println("\n  " + call[0] + " FAILED: " + e.getMessage());
                    System.out.println("    statement: " + sql);
                    System.exit(1);
                }
            }
            c.commit();

            System.out.println("==> Checking the result");
            check(count(c, "SELECT COUNT(*) FROM transactions") == before,
                "no transaction was lost (" + before + " before and after)");
            check(count(c, "SELECT COUNT(*) FROM transactions WHERE assetId = '" + KEEP + "'") == 3,
                "all 3 buys are on one holding now");
            check(count(c, "SELECT COUNT(*) FROM transactions WHERE assetId = '" + GONE + "'") == 0,
                "nothing still points at the removed entry");
            check(count(c, "SELECT COUNT(*) FROM assets WHERE id = '" + GONE + "'") == 0,
                "the duplicate asset row is gone");
            check(count(c, "SELECT COUNT(*) FROM assets WHERE id = '" + KEEP + "'") == 1,
                "the kept asset survived");

            // The ordering check. If source refs move after the asset delete,
            // the FK cascade eats this row and the holding loses live pricing.
            check(count(c, "SELECT COUNT(*) FROM asset_source_refs WHERE assetId = '" + KEEP
                + "' AND source = 'TRADEGATE'") == 1,
                "Tradegate price routing moved to the kept holding");
            check(count(c, "SELECT COUNT(*) FROM asset_source_refs WHERE assetId = '" + GONE + "'") == 0,
                "no source ref left pointing at a deleted asset");

            check(count(c, "SELECT COUNT(*) FROM asset_custody WHERE assetId = '" + KEEP
                + "' AND label = 'DEGIRO'") == 1,
                "the kept holding's own custody entry was not overwritten");
            check(count(c, "SELECT COUNT(*) FROM asset_custody WHERE assetId = '" + GONE + "'") == 0,
                "the losing custody row did not survive as an orphan");
            check(count(c, "SELECT COUNT(*) FROM watchlist WHERE assetId = '" + KEEP + "'") == 1,
                "the watchlist entry followed the holding");
            check(count(c, "SELECT COUNT(*) FROM corporate_actions WHERE assetId = '" + KEEP + "'") == 1,
                "the split event followed the lots it applies to");
            check(count(c, "SELECT COUNT(*) FROM price_candles WHERE assetId = '" + GONE + "'") == 0,
                "the removed entry's cached candles were cleared");
            check(count(c, "SELECT COUNT(*) FROM price_latest WHERE assetId = '" + GONE + "'") == 0,
                "the removed entry's cached price was cleared");
            check(count(c, "SELECT COUNT(*) FROM staking_balances WHERE assetId = '" + KEEP + "'") == 1,
                "the staking balance followed the holding");

            if (!ok) {
                System.out.println("\nFAILED: this merge would damage a real portfolio.");
                System.exit(1);
            }
            System.out.println("\nOK: 3 buys on 2 entries became 3 buys on 1, with routing intact.");
        }
    }
}
JAVA

NEWEST=$(ls "$SCHEMAS" | sed 's/\.json//' | sort -n | tail -1)
"$JAVA" -cp "$SQLITE_JAR" "$WORK/VerifyMerge.java" "$SCHEMAS" "$DAOS" "$MERGE" "$WORK" "$NEWEST"
