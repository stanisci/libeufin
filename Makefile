include build-system/config.mk

escaped_pwd = $(shell pwd | sed 's/\//\\\//g')

all: assemble
install: install-bank install-cli install-db-versioning # install-nexus 
git-archive-all = ./build-system/taler-build-scripts/archive-with-submodules/git_archive_all.py
git_tag=$(shell git describe --tags)
gradle_version=$(shell ./gradlew -q libeufinVersion)
define versions_check =
  if test $(git_tag) != "v$(gradle_version)"; \
    then echo WARNING: Project version from Gradle: $(gradle_version) differs from current Git tag: $(git_tag); fi
endef

sql_dir=$(prefix)/share/taler/sql/libeufin-bank
config_dir=$(prefix)/share/taler/config.d

.PHONY: dist
dist:
	@$(call versions_check)
	@mkdir -p build/distributions
	@$(git-archive-all) --include ./configure build/distributions/libeufin-$(shell ./gradlew -q libeufinVersion)-sources.tar.gz

.PHONY: exec-arch
exec-arch:
	@$(call versions_check)
	@./gradlew -q execArch

.PHONY: clean-spa
clean-spa:
	@rm -fr debian/usr/share/libeufin/demobank-ui/index.* debian/usr/share/libeufin/demobank-ui/*.svg

.PHONY: copy-spa
get-spa:
	@./contrib/copy_spa.sh

.PHONY: deb
deb: exec-arch copy-spa
	@dpkg-buildpackage -rfakeroot -b -uc -us

.PHONY: install-bank
install-bank:
	install -d $(config_dir)
	install contrib/libeufin-bank.conf $(config_dir)/
	@./gradlew -q -Pprefix=$(prefix) bank:installToPrefix; cd ..

# To reactivate after the refactoring.
# .PHONY: install-nexus
# install-nexus:
#	@./gradlew -q -Pprefix=$(prefix) nexus:installToPrefix; cd ..

.PHONY: install-cli
install-cli:
	@./gradlew -q replaceVersionCli
	@install -D cli/bin/libeufin-cli $(prefix)/bin

.PHONY: install-db-versioning
install-db-versioning:
	@install -D database-versioning/libeufin-bank*.sql -t $(sql_dir)
	@install -D database-versioning/versioning.sql -t $(sql_dir)
	@install -D database-versioning/procedures.sql -t $(sql_dir)

.PHONY: assemble
assemble:
	@./gradlew assemble

.PHONY: check
check:
	@./gradlew check

.PHONY: pofi-get
pofi-get:
	@./gradlew -q :nexus:pofi --args="download" # --args="arg1 arg2 .."

.PHONY: pofi-post
pofi-post:
	@./gradlew -q :nexus:pofi --args="upload"
