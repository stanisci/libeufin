# This Makefile has been placed under the public domain

include build-system/config.mk

# Default target, must be at the top.
# Should be changed with care to not break (Debian) packaging.
all: compile

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
bin_dir=$(abs_destdir)$(prefix)/bin
lib_dir=$(abs_destdir)$(prefix)/lib


# While the gradle command sounds like it's installing something,
# it's like a destdir install that only touches the source tree.
.PHONY: compile
compile:
	./gradlew bank:installShadowDist nexus:installShadowDist


.PHONY: dist
dist:
	$(call versions_check)
	mkdir -p build/distributions
	$(git-archive-all) --include ./configure build/distributions/libeufin-$(shell ./gradlew -q libeufinVersion)-sources.tar.gz

.PHONY: deb
deb:
	dpkg-buildpackage -rfakeroot -b -uc -us

# The install-nobuild-* targets install under the assumption
# that the compile step has already been run.

# Install without attempting to build first
.PHONY: install-nobuild
install-nobuild: install-nobuild-bank install-nobuild-nexus


.PHONY: install-nobuild-common
install-nobuild-common:
	install -m 644 -D -t $(config_dir) contrib/currencies.conf 
	install -m 644 -D -t $(sql_dir) database-versioning/versioning.sql 
	install -D -t $(bin_dir) contrib/libeufin-dbconfig

.PHONY: install-nobuild-bank-files
install-nobuild-bank-files:
	install -m 644 -D -t $(config_dir) contrib/bank.conf
	install -m 644 -D -t $(sql_dir) database-versioning/libeufin-bank*.sql
	install -m 644 -D -t $(sql_dir) database-versioning/libeufin-conversion*.sql

.PHONY: install-nobuild-nexus-files
install-nobuild-nexus-files:
	install -m 644 -D -t $(config_dir) contrib/nexus.conf
	install -m 644 -D -t $(sql_dir) database-versioning/libeufin-nexus*.sql

.PHONY: install-nobuild-bank
install-nobuild-bank: install-nobuild-common install-nobuild-bank-files
	install -d $(spa_dir)
	cp contrib/wallet-core/demobank/* $(spa_dir)/
	install -d $(abs_destdir)$(prefix)
	install -d $(bin_dir)
	install -d $(lib_dir)
	install -D -t $(bin_dir) contrib/libeufin-tan-*.sh
	install -D -t $(bin_dir) contrib/libeufin-bank-dbinit
	install -D -t $(bin_dir) bank/build/install/bank-shadow/bin/libeufin-bank
	install -m=644 -D -t $(lib_dir) bank/build/install/bank-shadow/lib/bank-*.jar

.PHONY: install-nobuild-nexus
install-nobuild-nexus: install-nobuild-common install-nobuild-nexus-files
	install -m 644 -D -t $(config_dir) contrib/nexus.conf
	install -m 644 -D -t $(man_dir)/man1 doc/prebuilt/man/libeufin-nexus.1
	install -m 644 -D -t $(man_dir)/man5 doc/prebuilt/man/libeufin-nexus.conf.5
	install -D -t $(bin_dir) contrib/libeufin-nexus-dbinit
	install -D -t $(bin_dir) nexus/build/install/nexus-shadow/bin/libeufin-nexus
	install -m 644 -D -t $(lib_dir) nexus/build/install/nexus-shadow/lib/nexus-*.jar

.PHONY: install
install:
	$(MAKE) compile
	$(MAKE) install-nobuild

.PHONY: assemble
assemble:
	./gradlew assemble

.PHONY: doc
doc:
	./gradlew dokkaHtmlMultiModule
	open build/dokka/htmlMultiModule/index.html

.PHONY: check
check: install-nobuild-bank-files
	./gradlew check

.PHONY: test
test: install-nobuild-bank-files
	./gradlew test --tests $(test) -i

.PHONY: nexus-test
nexus-test: install-nobuild-nexus-files
	./gradlew :nexus:test --tests $(test) -i

.PHONY: integration-test
integration-test: install-nobuild-bank-files install-nobuild-nexus-files
	./gradlew :integration:test --tests $(test) -i

.PHONY: integration
integration: install-nobuild-bank-files install-nobuild-nexus-files
	./gradlew :integration:run --console=plain --args="$(test)"

.PHONY: doc
doc:
	./gradlew dokkaHtmlMultiModule
	open build/dokka/htmlMultiModule/index.html
