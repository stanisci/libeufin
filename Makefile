include build-system/config.mk

escaped_pwd = $(shell pwd | sed 's/\//\\\//g')

all: assemble
install: install-nexus install-sandbox install-cli
git-archive-all = ./build-system/taler-build-scripts/archive-with-submodules/git_archive_all.py


.PHONY: dist
dist:
	@mkdir -p build/distributions
	@$(git-archive-all) --include ./configure build/distributions/libeufin-$(shell ./gradlew -q libeufinVersion)-sources.tar.gz

.PHONY: exec-arch
exec-arch:
	@./gradlew -q execArch

.PHONY: deb
deb: dist
	@dpkg-buildpackage -rfakeroot -b -uc -us


.PHONY: install-sandbox
install-sandbox:
	@./gradlew -q -Pprefix=$(prefix) sandbox:installToPrefix; cd ..

.PHONY: install-nexus
install-nexus:
	@./gradlew -q -Pprefix=$(prefix) nexus:installToPrefix; cd ..

.PHONY: install-cli
install-cli:
	@./gradlew -q replaceVersionCli
	@install -D cli/bin/libeufin-cli $(prefix)/bin

.PHONY: assemble
assemble:
	@./gradlew assemble

.PHONY: check
check:
	@./gradlew check
	@cd ./cli/tests && ./circuit_test.sh
	@cd ./cli/tests && ./debit_test.sh

# .PHONY: parse
# parse:
#	@cd parsing-tests; py.test -s checks.py
