SHELL := /bin/bash
VERSION ?= $(shell date +%y.%m.%d)

default:
	@echo USAGE: make release

release:
	git diff --exit-code > /dev/null # Working tree must be clean
	./mvnw versions:set -DnewVersion=$(VERSION)
	git commit -m "Release v$(VERSION)" .
	git tag -a -m "Release v$(VERSION)" "v$(VERSION)"
	./mvnw versions:set -DnextSnapshot=true
	git commit -m "Set version to next snapshot" .

dep:
	./mvnw clean install

test:
	./mvnw clean verify
