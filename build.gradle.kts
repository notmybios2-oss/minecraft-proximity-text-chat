plugins {
    java
}

group = "fr.mybios.onevs100"
version = "0.3.0"

repositories {
    mavenCentral()
    // Folia/Paper API
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Provided by the server at runtime (Folia 26.1.2 == Minecraft 26.1.2). Java 25 bytecode.
    compileOnly("dev.folia:folia-api:26.1.2.build.8-stable")

    // Tests that reference plugin classes implementing Bukkit interfaces (e.g. Listener) need the API
    // on the test classpath to link them, even when the assertions only exercise pure static logic.
    testImplementation("dev.folia:folia-api:26.1.2.build.8-stable")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    // folia-api is Java 25 bytecode (class major 69) -> must compile with a Java 25 toolchain.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Sources contain accented characters — pin the encoding so the build is reproducible
    // regardless of platform default.
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("prox-chat")
    manifest {
        // Runtime is Mojang-mapped. Declaring the namespace tells Paper's plugin remapper the jar is
        // already mojang-mapped so it must not rewrite anything.
        attributes("paperweight-mappings-namespace" to "mojang")
    }
}
