# SILLAGE — a salon management suite in pure Java.
# No dependencies, no wrapper, no network: JDK 17+ only.

JAVA   ?= java
JAVAC  ?= javac
JAR    ?= jar
MAIN   := build/classes
TEST   := build/test-classes
PORT   ?= 8080

.PHONY: all build jar test run clean reset help

all: build

build:
	./build.sh

jar:
	./build.sh jar

test: build
	$(JAVA) -cp $(MAIN):$(TEST) com.sillage.test.TestSuite

run: build
	$(JAVA) -cp $(MAIN) com.sillage.SillageApp --port=$(PORT)

reset: build
	$(JAVA) -cp $(MAIN) com.sillage.SillageApp --reset --port=$(PORT)

clean:
	rm -rf build

help:
	@echo "SILLAGE targets:"
	@echo "  make build   compile app + tests into build/"
	@echo "  make jar     build build/sillage.jar"
	@echo "  make test    run the dependency-free test suite"
	@echo "  make run     start on PORT=$(PORT) (demo data seeded on first boot)"
	@echo "  make reset   wipe ./data and reseed, then start"
	@echo "  make clean   remove build/"
