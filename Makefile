# This Makefile has been placed under the public domain

include build-system/config.mk

all: assemble
git-archive-all = ./build-system/taler-build-scripts/archive-with-submodules/git_archive_all.py
git_tag=$(shell git describe --tags)
gradle_version=$(shell ./gradlew -q libeufinVersion)

define versions_check =
  if test $(git_tag) != "v$(gradle_version)"; \
    then echo WARNING: Project version from Gradle: $(gradle_version) differs from current Git tag: $(git_tag); fi
endef

# Absolute DESTDIR or empty string if DESTDIR unset/empty
abs_destdir=$(abspath $(DESTDIR))

bank_sql_dir=$(abs_destdir)$(prefix)/share/libeufin-bank/sql
bank_config_dir=$(abs_destdir)$(prefix)/share/libeufin-bank/config.d
spa_dir=$(abs_destdir)$(prefix)/share/libeufin-bank/spa

# NOT installyed yet along "make install"
nexus_sql_dir=$(abs_destdir)$(prefix)/share/libeufin-nexus/sql
nexus_config_dir=$(abs_destdir)$(prefix)/share/libeufin-nexus/config.d

.PHONY: dist
dist:
	$(call versions_check)
	mkdir -p build/distributions
	$(git-archive-all) --include ./configure build/distributions/libeufin-$(shell ./gradlew -q libeufinVersion)-sources.tar.gz

.PHONY: deb
deb:
	dpkg-buildpackage -rfakeroot -b -uc -us

.PHONY: install
install: install-bank

install-bank-files:
	install -d $(bank_config_dir)
	install contrib/libeufin-bank.conf $(bank_config_dir)/
	install contrib/currencies.conf $(bank_config_dir)/
	install -D database-versioning/libeufin-bank*.sql -t $(bank_sql_dir)
	install -D database-versioning/libeufin-conversion.sql -t $(bank_sql_dir)
	install -D database-versioning/versioning.sql -t $(bank_sql_dir)

.PHONY: install-bank
install-bank: install-bank-files
	install -d $(spa_dir)
	cp contrib/wallet-core/demobank/* $(spa_dir)/
	./gradlew bank:installShadowDist
	install -d $(abs_destdir)$(prefix)
	cp -r bank/build/install/bank-shadow/* -d $(abs_destdir)$(prefix)
	cp -r contrib/libeufin-tan-*.sh -d $(abs_destdir)$(prefix)/bin

install-nexus:
	install -d $(nexus_config_dir)
	install contrib/libeufin-nexus.conf $(nexus_config_dir)/
	install -D database-versioning/libeufin-nexus*.sql -t $(nexus_sql_dir)
	install -D database-versioning/versioning.sql -t $(nexus_sql_dir)
	./gradlew nexus:installShadowDist
	cp -r nexus/build/install/nexus-shadow/* -d $(abs_destdir)$(prefix)

.PHONY: assemble
assemble:
	./gradlew assemble

.PHONY: check
check: install-bank-files
	./gradlew check

.PHONY: test
test: install-bank-files
	./gradlew test --tests $(test) -i
