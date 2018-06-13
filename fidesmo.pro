-injars target/fidesmo.jar(!com/sun/jna/sunos**,!com/sun/jna/w32ce-arm,!com/sun/jna/aix**,!com/sun/jna/linux-s390x**,!com/sun/jna/linux-ppc**,!com/sun/jna/openbsd-x86/**,!com/sun/jna/linux-mips64el/**,!com/sun/jna/freebsd-x86/**)
-libraryjars <java.home>/lib/rt.jar

-keep class com.fidesmo.** { *; }
-keep public class * extends java.security.Provider {*;}
-keep public class * extends javax.smartcardio.** {*;}

-keep class com.sun.jna.** { *; }
-keep class jnasmartcardio.** { *; }
-keep class org.apache.commons.logging.impl.LogFactoryImpl

-optimizations !code/allocation/variable

-dontobfuscate
-dontnote !com.fidesmo.**
-dontwarn !com.fidesmo.**

-outjars fidesmo.jar
