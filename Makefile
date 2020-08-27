SHELL := /bin/bash
VERSION ?= $(shell date +%y.%m.%d)

default:
	@echo USAGE: make release

release:
	git diff --exit-code > /dev/null # Working tree must be clean
	./mvnw versions:set -DnewVersion=$(VERSION)
	git commit -m "Release v$(shell egrep -o "([0-9]{1,}\.)+[0-9]{2,}" pom.xml | head -1)" .
	git tag "v$(shell egrep -o "([0-9]{1,}\.)+[0-9]{2,}" pom.xml | head -1)"
	./mvnw versions:set -DnextSnapshot=true
	git commit -m "Set version to next snapshot" .
