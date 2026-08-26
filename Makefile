# Eddies: everything runs inside the build container.
COMPOSE := docker compose -f docker/docker-compose.yml

.DEFAULT_GOAL := help
.PHONY: help build app debug demo release test lint shell clean

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
