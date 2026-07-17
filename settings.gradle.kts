plugins {
    // Auto-provisions the JDK toolchain build.gradle.kts asks for (Java 25 here — folia-api bytecode)
    // on any machine/CI without a matching JDK preinstalled.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "prox-chat"
