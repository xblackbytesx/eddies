# Eddies: everything runs inside the build container.
COMPOSE := docker compose -f docker/docker-compose.yml

.DEFAULT_GOAL := help
.PHONY: help build app debug demo release test lint shell clean keystore

## Lists the targets. Bare `make` lands here rather than guessing.
help:
	@echo "Eddies: run everything through the build container:"
	@echo
	@echo "  make build     debug APK   → build-output/eddies-full-*.apk"
	@echo "  make demo      demo APK for screenshots, installs alongside the real app"
	@echo "  make release   release APKs → build-output/eddies-full-*.apk"
	@echo "  make test      JVM unit tests, the fast gate, run before build"
	@echo "  make lint"
	@echo "  make shell     interactive container for one-off gradle tasks"
	@echo "  make clean     remove build dirs and build-output"
	@echo "  make keystore  generate a release signing key, print it for CI"
	@echo
	@echo "Every build prints the APKs it produced, with timestamps. Check them."

## Debug APK for the real app
build:
	$(COMPOSE) run --rm build

## Demo APK: a separate app with a fake portfolio, for screenshots
#
# Installs alongside the real one as com.eddies.app.demo. A different
# applicationId means a different data directory, so it cannot read the real
# ledger. That is the whole reason it is a flavour and not a setting.
demo:
	$(COMPOSE) run --rm demo

# Aliases for the names a person actually reaches for. Without them `make app`
# *silently succeeds doing nothing*: `app/` is a real directory, so make decides
# the target is already up to date, prints "Nothing to be done" and exits 0.
# `.PHONY` above is what makes these fire despite the directory existing.
app debug: build

## Release APK (signed if keystore.properties/env present)
release:
	$(COMPOSE) run --rm build-release

## JVM unit tests, the fast gate. Run before build
test:
	$(COMPOSE) run --rm test

lint:
	$(COMPOSE) run --rm lint

## Interactive container for one-off gradle tasks
shell:
	$(COMPOSE) run --rm shell

## Generates a release signing key and prints it base64 encoded, for CI
#
# There is no seed and no way to regenerate this. keytool draws the RSA key from
# the system CSPRNG, so the keystore *is* the secret. Lose it and you can never
# ship an update anyone can install over an existing one: Android matches by
# signature and there is no recovery path.
#
# So the output is printed rather than left lying around, and the file is
# removed on the way out. Put the base64 into the RELEASE_KEYSTORE_BASE64
# repository secret, and put the keystore itself somewhere you would keep an SSH
# key. GitHub secrets are write-only, so that second copy is the only way back.
#
# Runs in the same JDK image the build uses, so keytool is not a host
# requirement. PKCS12 explicitly: it is keytool's default now, and asking for it
# avoids both the migration warning and the proprietary JKS format.
KEYSTORE_ALIAS ?= eddies
KEYSTORE_FILE  ?= release.jks
keystore:
	@test ! -f "$(KEYSTORE_FILE)" || \
		{ echo "$(KEYSTORE_FILE) already exists. Move it aside first." >&2; exit 1; }
	$(COMPOSE) run --rm --entrypoint keytool build \
		-genkeypair -v -keystore "/workspace/$(KEYSTORE_FILE)" \
		-alias "$(KEYSTORE_ALIAS)" -keyalg RSA -keysize 4096 \
		-validity 10000 -storetype PKCS12
	@echo
	@echo "=== RELEASE_KEYSTORE_BASE64, paste this into the repository secret ==="
	@$(COMPOSE) run --rm -T --entrypoint base64 build -w 0 "/workspace/$(KEYSTORE_FILE)"
	@echo
	@echo
	@echo "Now, before anything else:"
	@echo "  1. Attach $(KEYSTORE_FILE) to your password manager."
	@echo "  2. Paste the base64 above into RELEASE_KEYSTORE_BASE64."
	@echo "  3. Add RELEASE_KEYSTORE_PASSWORD (the password you just typed),"
	@echo "     RELEASE_KEY_ALIAS ($(KEYSTORE_ALIAS)), and RELEASE_KEY_PASSWORD."
	@echo
	@echo "     RELEASE_KEY_PASSWORD is the SAME password. PKCS12 does not support"
	@echo "     a separate key password, which is why keytool only asked once."
	@echo "     Leave it unset and the build silently produces unsigned APKs"
	@echo "     instead of failing, because Gradle only wires up signing when all"
	@echo "     four values are present."
	@echo "  4. Then delete $(KEYSTORE_FILE) from here. It is gitignored, so it will"
	@echo "     not be committed, but it should not sit in a working tree either."
	@echo
	@echo "This target deliberately does not delete it for you. It cannot be"
	@echo "regenerated, and GitHub will not let you read the secret back, so the"
	@echo "copy in your password manager is the only way back from a lost key."

## Removes build output. Runs in the container, which is the point.
#
# `rm -rf` on the host cannot delete what Gradle wrote as root inside the
# container, so a host-side clean fails exactly when you need it most: after an
# interrupted build has left root-owned files behind. Running as root in the
# container always works. `run-gradle.sh` now restores ownership even on
# Ctrl-C, so this should be a belt-and-braces path rather than a routine one.
clean:
	$(COMPOSE) run --rm --entrypoint sh build -c \
		'rm -rf /workspace/build /workspace/app/build /workspace/build-output \
		        /workspace/.gradle /workspace/app/.gradle /workspace/.kotlin'

# A mistyped goal must fail, not succeed quietly.
.DEFAULT:
	@echo "make: no such target '$@', try 'make help'" >&2; exit 2

# Guard: without this, .DEFAULT fires while make considers remaking the makefile
# itself, and every invocation dies with "no such target 'Makefile'".
Makefile: ;
