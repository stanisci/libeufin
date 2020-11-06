include build-system/config.mk

all: install-nexus
install-sandbox:
	@./gradlew -q -Pprefix=$(prefix) sandbox:installToPrefix; cd ..

install-nexus:
	@./gradlew -q -Pprefix=$(prefix) nexus:installToPrefix; cd ..
