include build-system/config.mk

escaped_pwd = $(shell pwd | sed 's/\//\\\//g')

install: install-nexus install-sandbox install-cli

.PHONY: dist
dist:
	@echo Creating the 'dist' Zip archive.
	@./gradlew -q dist

.PHONY: install-sandbox
install-sandbox:
	@echo Installing Sandbox.
	@./gradlew -q -Pprefix=$(prefix) sandbox:installToPrefix; cd ..

.PHONY: install-nexus
install-nexus:
	@echo Installing Nexus.
	@./gradlew -q -Pprefix=$(prefix) nexus:installToPrefix; cd ..

.PHONY: install-cli
install-cli:
	@echo Installing CLI.
	@install -D cli/bin/libeufin-cli $(prefix)/bin

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
