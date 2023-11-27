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

man_dir=$(abs_destdir)$(prefix)/share/man
spa_dir=$(abs_destdir)$(prefix)/share/libeufin/spa
sql_dir=$(abs_destdir)$(prefix)/share/libeufin/sql
config_dir=$(abs_destdir)$(prefix)/share/libeufin/config.d

.PHONY: dist
dist:
	$(call versions_check)
	mkdir -p build/distributions
	$(git-archive-all) --include ./configure build/distributions/libeufin-$(shell ./gradlew -q libeufinVersion)-sources.tar.gz

.PHONY: deb
deb:
	dpkg-buildpackage -rfakeroot -b -uc -us

.PHONY: install
install: install-bank install-nexus


.PHONY: install-common
install-common:
	install -D -t $(config_dir) contrib/currencies.conf 
	install -D -t $(sql_dir) database-versioning/versioning.sql 

.PHONY: install-bank-files
install-bank-files:
	install -D -t $(config_dir) contrib/bank.conf
	install -D -t $(sql_dir) database-versioning/libeufin-bank*.sql
	install -D -t $(sql_dir) database-versioning/libeufin-conversion*.sql

.PHONY: install-bank
install-bank: install-common install-bank-files
	install -d $(spa_dir)
	cp contrib/wallet-core/demobank/* $(spa_dir)/
	./gradlew bank:installShadowDist
	install -d $(abs_destdir)$(prefix)
	rm -f bank/build/install/bank-shadow/bin/*.bat
	cp -r bank/build/install/bank-shadow/* -d $(abs_destdir)$(prefix)
	cp -r contrib/libeufin-tan-*.sh -d $(abs_destdir)$(prefix)/bin
	cp contrib/libeufin-bank-dbinit -d $(abs_destdir)$(prefix)/bin
	cp contrib/libeufin-bank-dbconfig -d $(abs_destdir)$(prefix)/bin

.PHONY: install-nexus
install-nexus: install-common
	install -D -t $(config_dir) contrib/nexus.conf
	install -D -t $(sql_dir) database-versioning/libeufin-nexus*.sql
	install -D -t $(man_dir)/man1 doc/prebuilt/man/libeufin-nexus.1
	install -D -t $(man_dir)/man5 doc/prebuilt/man/libeufin-nexus.conf.5
	./gradlew nexus:installShadowDist
	rm -f nexus/build/install/nexus-shadow/bin/*.bat
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
