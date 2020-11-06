include build-system/config.mk

install: install-nexus
install-sandbox:
	@echo Installing sandbox.
	@./gradlew -q -Pprefix=$(prefix) sandbox:installToPrefix; cd ..

install-nexus:
	@echo Installing nexus.
	@./gradlew -q -Pprefix=$(prefix) nexus:installToPrefix; cd ..
