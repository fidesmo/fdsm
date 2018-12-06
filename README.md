# FiDeSMo Utility · [![MIT licensed](https://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/fidesmo/fdsm/blob/master/LICENSE) [![Build Status](https://travis-ci.org/fidesmo/fdsm.svg?branch=master)](https://travis-ci.org/fidesmo/fdsm)

FDSM is _the_ developer's Swiss Army Knife for working with a [Fidesmo device](https://www.fidesmo.com/fidesmo/devices/) and the [Fidesmo API](https://developer.fidesmo.com/api). It supersedes [gradle-fidesmo](https://github.com/fidesmo/gradle-fidesmo) for interacting with the Fidesmo API and provides an alternative to [Fidesmo Android app](https://play.google.com/store/apps/details?id=com.fidesmo.sec.android) when delivering services to a device via a PC/SC reader (contact or contactless). All from the command line.

# Getting started

You will need the following:
- [Java 1.8](http://www.oracle.com/technetwork/java/javase/downloads/jre8-downloads-2133155.html) for running the app
- a [Fidesmo Developer account](https://developer.fidesmo.com) for your `$FIDESMO_APP_ID` and `$FIDESMO_APP_KEY`
- a Fidesmo device (get one from the [shop](http://shop.fidesmo.com))
- a PC/SC NFC reader (unless your developer device is the [Yubikey NEO with Fidesmo](http://shop.fidesmo.com/product/yubikey-neo-with-fidesmo))

Grab a copy of `fdsm.jar` (or `fdsm.exe` wrapper for Windows) from the [release area](https://github.com/fidesmo/fdsm/releases). It is an executable JAR file, so start by running

    java -jar fdsm.jar -h

or `fdsm.exe -h` on Windows. With Linux or macOS, it is recommended to add a handy alias to your `~/.bashrc` or `~/.profile` (assuming ~/Downloads is where you saved fdsm.jar):

    alias fdsm="java -jar ~/Downloads/fdsm.jar"


## HOWTO
FDSM should be easy to use for [JavaCard developers](https://developer.fidesmo.com/fidesmo-for-card-developers) with some knowledge of the Fidesmo API. We will cover the full development cycle with the following HOWTO-s

 1. [Develop and build your JavaCard applet](https://github.com/fidesmo/fdsm/wiki/Applet-Development) with [`ant-javacard`](https://github.com/martinpaljak/ant-javacard)
 2. [Test your applet on a Fidesmo device](https://github.com/fidesmo/fdsm/wiki/Install-and-Personalize) with `fdsm`
 3. [Manage your application and associated applets in the Fidesmo API](https://github.com/fidesmo/fdsm/wiki/Applet-Management) with `fdsm`


## Support
 - please [open a new issue on Github](https://github.com/fidesmo/fdsm/issues/new) if you find a mistake in code or documentation
 - for general support, please contact Fidesmo support via support@fidesmo.com

## License and development
FDSM is open source software, developed by [Fidesmo AB](https://www.fidesmo.com) and licensed under [MIT license](https://github.com/fidesmo/fdsm/blob/master/LICENSE). Pull requests are most welcome, please refer to [CONTRIBUTING.md](https://github.com/fidesmo/fdsm/blob/master/CONTRIBUTING.md).

To contribute you will need [Apache Maven](https://maven.apache.org) to build and test the software.

To produce a build, execute `mvn package` and use the generated `target/fdsm.jar`.

### Environment variables
`fdsm` is a command line application and takes most of its input from
command line arguments but some behavior can be tuned by setting environment
variables.

- `FIDESMO_APP_ID` - the app ID from developer portal, equivalent of using `--app-id`
- `FIDESMO_APP_KEY` - the app key from developer portal, equivalent of using `--app-key`
- `FIDESMO_DEVELOPER` - set to `true` to show otherwise hidden apps
- `FIDESMO_API_URL` - the URL of the the Fidesmo backend (do not change if unsure)
