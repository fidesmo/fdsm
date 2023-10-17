# FiDeSMo Utility · [![MIT licensed](https://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/fidesmo/fdsm/blob/master/LICENSE) [![Build Status](https://github.com/fidesmo/fdsm/workflows/Continuous%20Integration/badge.svg?branch=master)](https://github.com/fidesmo/fdsm/actions) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.fidesmo/fdsm/badge.svg)](https://mvnrepository.com/artifact/com.fidesmo/fdsm) [![Release](	https://img.shields.io/github/release/fidesmo/fdsm/all.svg)](https://github.com/fidesmo/fdsm/releases)

FDSM is _the_ developer's Swiss Army Knife for working with a [Fidesmo device](https://fidesmo.com/consumer/wearables/) and the [Fidesmo API](https://developer.fidesmo.com/api). It provides an alternative to [Fidesmo Android app](https://play.google.com/store/apps/details?id=com.fidesmo.sec.android) when delivering services to a device via a PC/SC reader (contact or contactless). All from the command line.

# Getting started

You will need the following:
- Latest [Java 11](https://adoptopenjdk.net/) for running the app
- a [Fidesmo Developer account](https://fidesmo.com/book-demo/) for your `$FIDESMO_AUTH` token and `$FIDESMO_APPID`
- a Fidesmo device (get one from the [shop](http://shop.fidesmo.com))
- a [PC/SC NFC reader](https://github.com/fidesmo/fdsm/wiki/Choosing-a-desktop-reader)

Grab a copy of `fdsm.jar` (or `fdsm.exe` wrapper for Windows) from the [release area](https://github.com/fidesmo/fdsm/releases). It is an executable JAR file, so start by running

    java -jar fdsm.jar -h

or `fdsm.exe -h` on Windows. With Linux or macOS, it is recommended to add a handy alias to your `~/.bashrc` or `~/.profile` (assuming ~/Downloads is where you saved fdsm.jar):

    alias fdsm="java -jar ~/Downloads/fdsm.jar"


## HOWTO
FDSM should be easy to use for [JavaCard developers](https://fidesmo.com/technology/java-card/) with some knowledge of the Fidesmo API. We will cover the full development cycle with the following HOWTO-s

 1. [Develop and build your JavaCard applet](https://github.com/fidesmo/fdsm/wiki/Applet-Development) with [`ant-javacard`](https://github.com/martinpaljak/ant-javacard)
 2. [Test your applet on a Fidesmo device](https://github.com/fidesmo/fdsm/wiki/Install-and-Personalize) with `fdsm`
 3. [Manage your application and associated applets in the Fidesmo API](https://github.com/fidesmo/fdsm/wiki/Applet-Management) with `fdsm`


## Support
 - please [open a new issue on Github](https://github.com/fidesmo/fdsm/issues/new) if you find a mistake in code or documentation
 - for general support, please contact Fidesmo support via support@fidesmo.com

## License and development
FDSM is open source software, developed by [Fidesmo AB](https://www.fidesmo.com) and licensed under [MIT license](https://github.com/fidesmo/fdsm/blob/master/LICENSE). Pull requests are most welcome, please refer to [CONTRIBUTING.md](https://github.com/fidesmo/fdsm/blob/master/CONTRIBUTING.md).

To contribute you will need [Apache Maven](https://maven.apache.org) to build and test the
software, but a wrapper to download the right version is included.

To produce a build, execute `./mvnw package` and use the generated `target/fdsm.jar`.

#### Releasing
- To release run `make release` on up-to-date main/master branch.
- After checking the output of the tests, `git push` to move to next version.
- Run `git push --tags` to push the tag (generated in first step) and trigger automatic release with Github Actions.
  - Manually pushing any tag named `v*` will also trigger the release. 
- Update release description with the latest changes.

## Environment variables
`fdsm` is a command line application and takes most of its input from
command line arguments but some behavior can be tuned by setting environment
variables.

- `FIDESMO_AUTH` - user and password, or token. Equivalent of using `--auth user:password` or `--auth token`
- `FIDESMO_API_URL` - the URL of the Fidesmo backend (do not change if unsure)
- `FIDESMO_APPID` - the Application ID to use. Equivalent of using `--app-id <id>`
