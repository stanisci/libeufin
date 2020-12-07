include build-system/config.mk

escaped_pwd = $(shell pwd | sed 's/\//\\\//g')

install: install-nexus install-cli
install-dev: install-nexus-dev install-sandbox-dev install-cli

.PHONY: install-sandbox
install-sandbox:
	@echo Installing Sandbox.
	@./gradlew -q -Pprefix=$(prefix) sandbox:installToPrefix; cd ..

.PHONY: install-sandbox-dev
install-sandbox-dev:
	@echo Installing Sandbox "dev".
	@sed 's/PROJECT/$(escaped_pwd)/' sandbox/libeufin-sandbox-dev-template > sandbox/libeufin-sandbox-dev
	@install -D sandbox/libeufin-sandbox-dev $(prefix)/bin/libeufin-sandbox

.PHONY: install-nexus
install-nexus:
	@echo Installing Nexus.
	@./gradlew -q -Pprefix=$(prefix) nexus:installToPrefix; cd ..

.PHONY: install-nexus-dev
install-nexus-dev:
	@echo Installing Nexus "dev".
	@sed 's/PROJECT/$(escaped_pwd)/' nexus/libeufin-nexus-dev-template > nexus/libeufin-nexus-dev
	@install -D nexus/libeufin-nexus-dev $(prefix)/bin/libeufin-nexus

.PHONY: install-cli
install-cli:
	@echo Installing CLI.
	@install -D cli/libeufin-cli $(prefix)/bin

.PHONY: assemble
assemble:
	@./gradlew assemble

.PHONY: check
check:
	@./gradlew check


.PHONY: tests
tests:
	@cd integration-tests; py.test -k "not test_env" tests.py

.PHONY: parse
parse:
	@cd parsing-tests; py.test -s checks.py
