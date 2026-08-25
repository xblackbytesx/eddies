#!/bin/bash
# Runs a Gradle task inside the build container, then restores host ownership
# of everything Gradle wrote into the bind mount.
set -e
cd /workspace

# Anything in build-output older than this did not come from this run.
STARTED_AT=$(date +%s)

HOST_UID=$(stat -c %u settings.gradle.kts)
HOST_GID=$(stat -c %g settings.gradle.kts)

# Restore host ownership no matter how this script ends.
#
# Gradle runs as root in here, so everything it writes into the bind mount is
# root-owned until this runs. It used to sit after the gradle call, which meant
# a failed build or a Ctrl-C skipped it entirely and left thousands of
# root-owned files in the user's working tree. The next build then died with
# "java.nio.file.AccessDeniedException" on a generated source file, and no
# amount of `make clean` fixed it because rm ran as the host user too.
#
# EXIT covers success and `set -e` failures; INT and TERM cover Ctrl-C, which is
# how it was found.
restore_ownership() {
    chown -R "$HOST_UID:$HOST_GID" \
        .gradle build build-output app/build app/.gradle keystore .kotlin \
        2>/dev/null || true
}
trap restore_ownership EXIT INT TERM

# Stable debug keystore: committed once, generated here if absent, so every
# debug build signs identically and reinstalls never conflict.
if [ ! -f keystore/debug.keystore ]; then
    mkdir -p keystore
    keytool -genkeypair -v \
        -keystore keystore/debug.keystore \
        -storepass android -keypass android \
        -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Eddies Debug,O=Eddies,C=NL"
fi

gradle --no-daemon "$@"

# ABI splits mean several APKs per build type, so copy whatever is there rather
# than naming each one. The universal build carries every ABI; the per-ABI ones
# are a third the size.
mkdir -p build-output
for apk in app/build/outputs/apk/debug/*.apk app/build/outputs/apk/release/*.apk; do
    [ -f "$apk" ] || continue
    base=$(basename "$apk")
    cp "$apk" "build-output/${base/app-/eddies-}"
done

# Ownership is restored by the EXIT trap above, which also covers the paths a
# failed or interrupted run wrote.

# Say what was produced, and flag anything left over from an earlier build.
#
# The copies above are conditional and silent, so a Gradle run that succeeds
# without producing an APK would otherwise leave a stale artifact sitting in
# build-output/ looking exactly like a fresh one. A build whose output cannot be
# told apart from a no-op is the defect; this is the fix.
echo
echo "── build-output ────────────────────────────────────────────────────"
if compgen -G "build-output/*.apk" > /dev/null; then
    for apk in build-output/*.apk; do
        mtime=$(stat -c %Y "$apk")
        size=$(du -h "$apk" | cut -f1)
        when=$(date -d "@$mtime" '+%Y-%m-%d %H:%M:%S')
        if [ "$mtime" -ge "$STARTED_AT" ]; then
            printf '   fresh  %-36s %6s  %s\n' "$apk" "$size" "$when"
        else
            printf '   STALE  %-36s %6s  %s  ← NOT from this run\n' \
                "$apk" "$size" "$when"
        fi
    done
else
    echo "   no APKs, expected for 'test' and 'lint'"
fi
echo
