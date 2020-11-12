include build-system/config.mk

install: install-nexus install-cli
install-sandbox:
	@echo Installing sandbox.
	@./gradlew -q -Pprefix=$(prefix) sandbox:installToPrefix; cd ..

install-nexus:
	@echo Installing nexus.
	@./gradlew -q -Pprefix=$(prefix) nexus:installToPrefix; cd ..

install-cli:
	@echo Installing CLI.
	@cp cli/libeufin-cli $(prefix)/bin

assemble:
	@./gradlew assemble
