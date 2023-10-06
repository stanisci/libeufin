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

sql_dir=$(abs_destdir)$(prefix)/share/libeufin-bank/sql
config_dir=$(abs_destdir)$(prefix)/share/libeufin-bank/config.d

.PHONY: dist
dist:
	$(call versions_check)
	mkdir -p build/distributions
	$(git-archive-all) --include ./configure build/distributions/libeufin-$(shell ./gradlew -q libeufinVersion)-sources.tar.gz

.PHONY: exec-arch
exec-arch:
	$(call versions_check)
	./gradlew -q execArch

.PHONY: clean-spa
clean-spa:
	rm -fr debian/usr/share/libeufin/demobank-ui/index.* debian/usr/share/libeufin/demobank-ui/*.svg

.PHONY: copy-spa
get-spa:
	./contrib/copy_spa.sh

.PHONY: deb
deb: exec-arch copy-spa
	dpkg-buildpackage -rfakeroot -b -uc -us


.PHONY: install
install:
	install -d $(config_dir)
	install contrib/libeufin-bank.conf $(config_dir)/
	install -D database-versioning/libeufin-bank*.sql -t $(sql_dir)
	install -D database-versioning/versioning.sql -t $(sql_dir)
	install -D database-versioning/procedures.sql -t $(sql_dir)
	./gradlew -q -Pprefix=$(abs_destdir)$(prefix) bank:installToPrefix

.PHONY: assemble
assemble:
	./gradlew assemble

.PHONY: check
check:
	./gradlew check
