#!/usr/bin/env bash
# Runs the real migration chain against a real SQLite database and compares the
# result with the schema Room expects.
#
# Why this exists: migration SQL is a string, so the compiler cannot check it and
# the JVM test suite cannot execute it. The first version of this check hand
# copied the statements into the test, which meant it verified the intent rather
# than the artifact. It passed while the shipped app crashed on launch with
# "no such column: CRYPTO", because a quote had been lost from 'CRYPTO' in the
# source and the copy in the test still had it.
#
# So: the statements are EXTRACTED FROM THE SOURCE FILE. Never paste them here.
#
# Needs a local JDK and sqlite-jdbc. See AGENTS.md.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB_SOURCE="$REPO_ROOT/app/src/main/java/com/eddies/app/data/db/EddiesDatabase.kt"
SCHEMAS="$REPO_ROOT/app/schemas/com.eddies.app.data.db.EddiesDatabase"

JAVA="${JAVA:-java}"
SQLITE_JAR="${SQLITE_JAR:?set SQLITE_JAR to a sqlite-jdbc jar}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> Extracting migration statements from $(basename "$DB_SOURCE")"
# Each execSQL("...") call, with Kotlin string concatenation joined back up.
# Deliberately reads the source, so a typo in the source is a failure here.
# POSIX awk only: match() with a capture array is a gawk extension and this
# runs on mawk in the build container.
awk '
  /val MIGRATION_[0-9]+_[0-9]+ = object : Migration\(/ {
    if (match($0, /MIGRATION_[0-9]+_[0-9]+/)) {
      current = substr($0, RSTART + 10, RLENGTH - 10)
    }
  }
  /connection\.execSQL\(/ { collecting = 1; buf = "" }
  collecting {
    line = $0
    while (match(line, /"([^"\\]|\\.)*"/)) {
      s = substr(line, RSTART + 1, RLENGTH - 2)
      gsub(/\\"/, "\"", s)
      buf = buf s
      line = substr(line, RSTART + RLENGTH)
    }
    if (/\)$/ || /\),$/) {
      if (buf != "") print current "\t" buf
      collecting = 0
    }
  }
' "$DB_SOURCE" > "$WORK/migrations.tsv"

echo "    $(wc -l < "$WORK/migrations.tsv") statements across $(cut -f1 "$WORK/migrations.tsv" | sort -u | wc -l) migrations"
cut -f1 "$WORK/migrations.tsv" | sort -u | sed 's/^/      /'

cat > "$WORK/Verify.java" <<'JAVA'
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Builds the oldest exported schema, replays the real migrations, diffs against fresh. */
public class Verify {

    record Col(String name, String type, boolean notNull, int pk) {}

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

    static Map<String, List<Col>> tables(Connection c) throws Exception {
        Map<String, List<Col>> out = new TreeMap<>();
        List<String> names = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' "
                 + "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%'")) {
            while (rs.next()) names.add(rs.getString(1));
        }
        for (String t : names) {
            List<Col> cols = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA table_info(`" + t + "`)")) {
                while (rs.next()) cols.add(new Col(rs.getString("name"),
                    rs.getString("type").toUpperCase(), rs.getInt("notnull") == 1, rs.getInt("pk")));
            }
            cols.sort(Comparator.comparing(Col::name));
            out.put(t, cols);
        }
        return out;
    }

    static Set<String> indices(Connection c) throws Exception {
        Set<String> out = new TreeSet<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT name, tbl_name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'")) {
            while (rs.next()) out.add(rs.getString("tbl_name") + "." + rs.getString("name"));
        }
        return out;
    }

    public static void main(String[] a) throws Exception {
        Path schemaDir = Path.of(a[0]);
        Path migrationsTsv = Path.of(a[1]);
        int oldest = Integer.parseInt(a[2]);
        int newest = Integer.parseInt(a[3]);
        Path work = migrationsTsv.getParent();

        Path migrated = work.resolve("migrated.db");
        Files.deleteIfExists(migrated);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + migrated)) {
            try (Statement st = c.createStatement()) {
                for (String sql : createSql(schemaDir, oldest)) st.execute(sql);
            }
            System.out.println("    built v" + oldest + " from Room's exported schema");

            // Seed a row so an INSERT ... SELECT is actually exercised. An empty
            // table makes a broken data migration look fine.
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO portfolio_snapshots (day, totalValue, costBasis, currency, takenAt) "
                    + "VALUES ('2026-01-01', '100', '80', 'EUR', 1)");
            } catch (SQLException ignored) { }

            List<String> lines = Files.readAllLines(migrationsTsv);
            for (int v = oldest; v < newest; v++) {
                String key = v + "_" + (v + 1);
                int ran = 0;
                for (String line : lines) {
                    String[] parts = line.split("\t", 2);
                    if (parts.length < 2 || !parts[0].equals(key)) continue;
                    try (Statement st = c.createStatement()) {
                        st.execute(parts[1]);
                        ran++;
                    } catch (SQLException e) {
                        // Name the statement. A raw stack trace here says the
                        // database is unhappy but not which line to go and fix.
                        System.out.println("\n  MIGRATION " + key + " FAILED");
                        System.out.println("    " + e.getMessage());
                        System.out.println("    statement: " + parts[1]);
                        System.out.println("\nFAILED: this would crash on launch for anyone upgrading.");
                        System.exit(1);
                    }
                }
                System.out.println("    migration " + key + ": " + ran + " statements ok");
            }

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT assetClass FROM portfolio_snapshots LIMIT 1")) {
                if (rs.next()) System.out.println("    carried-over row has assetClass=" + rs.getString(1));
            } catch (SQLException e) {
                System.out.println("    (no carried-over row to check)");
            }

            Path fresh = work.resolve("fresh.db");
            Files.deleteIfExists(fresh);
            Map<String, List<Col>> freshTables;
            Set<String> freshIdx;
            try (Connection f = DriverManager.getConnection("jdbc:sqlite:" + fresh);
                 Statement st = f.createStatement()) {
                for (String sql : createSql(schemaDir, newest)) st.execute(sql);
                freshTables = tables(f);
                freshIdx = indices(f);
            }

            Map<String, List<Col>> got = tables(c);
            Set<String> gotIdx = indices(c);
            boolean clean = true;
            Set<String> all = new TreeSet<>(got.keySet());
            all.addAll(freshTables.keySet());
            for (String t : all) {
                if (!got.containsKey(t)) { System.out.println("  MISSING table: " + t); clean = false; }
                else if (!freshTables.containsKey(t)) { System.out.println("  EXTRA table: " + t); clean = false; }
                else if (!got.get(t).equals(freshTables.get(t))) {
                    System.out.println("  COLUMNS DIFFER in " + t);
                    System.out.println("    expected: " + freshTables.get(t));
                    System.out.println("    got:      " + got.get(t));
                    clean = false;
                }
            }
            if (!gotIdx.equals(freshIdx)) {
                Set<String> missing = new TreeSet<>(freshIdx); missing.removeAll(gotIdx);
                Set<String> extra = new TreeSet<>(gotIdx); extra.removeAll(freshIdx);
                if (!missing.isEmpty()) System.out.println("  MISSING indices: " + missing);
                if (!extra.isEmpty()) System.out.println("  EXTRA indices: " + extra);
                clean = false;
            }
            if (!clean) { System.out.println("\nFAILED: Room would reject this on open."); System.exit(1); }
            System.out.println("\nOK: migrated schema matches a fresh one, and every statement executed.");
        }
    }
}
JAVA

OLDEST=$(ls "$SCHEMAS" | sed 's/\.json//' | sort -n | head -1)
NEWEST=$(ls "$SCHEMAS" | sed 's/\.json//' | sort -n | tail -1)
echo "==> Replaying v$OLDEST to v$NEWEST"
"$JAVA" -cp "$SQLITE_JAR" "$WORK/Verify.java" "$SCHEMAS" "$WORK/migrations.tsv" "$OLDEST" "$NEWEST"
