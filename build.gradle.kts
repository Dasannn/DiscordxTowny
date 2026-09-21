import java.time.Duration
import java.util.zip.ZipFile

plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.2.2"
}

group = "com.discordtowny"
// The release pipeline passes -PreleaseVersion=<tag without the leading v>, so the
// jar name, paper-plugin.yml and the GitHub tag can never disagree about the version.
version = (findProperty("releaseVersion") ?: "0.1.0-SNAPSHOT").toString()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.glaremasters.me/repository/towny/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.124-stable")
    compileOnly("com.palmergames.bukkit.towny:towny:0.103.2.0")

    implementation("net.dv8tion:JDA:6.6.0") {
        // El plugin no reproduce audio: fuera la pila de voz.
        exclude(module = "opus-java")
    }
    implementation("com.zaxxer:HikariCP:7.1.0")

    // Driver SQLite necesario en runtime (SQLite es la alternativa automatica a MySQL/MariaDB).
    // Unico cambio fuera de storage/ autorizado por la ficha T2.
    implementation("org.xerial:sqlite-jdbc:3.51.0.0")

    // El servidor los aporta en runtime, pero los tests si los necesitan en el
    // classpath: sin esto no compila nada que toque Bukkit, Adventure o Towny.
    testImplementation("io.papermc.paper:paper-api:26.2.build.124-stable")
    testImplementation("com.palmergames.bukkit.towny:towny:0.103.2.0")

    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()

    // A test that leaves a thread blocked forever does not fail: the JVM simply
    // never exits and the build hangs with nothing to point at. One such test cost
    // CI forty minutes before it was cancelled by hand. A run that stops making
    // progress must fail instead of waiting for the six-hour job limit.
    timeout.set(Duration.ofMinutes(20))

    // When the suite stalls, the timeout above reports the task and nothing else:
    // no name, no class, no hint of which test stopped making progress. Printing
    // every start on CI leaves the culprit as the last line of the log.
    if (System.getenv("CI") != null) {
        testLogging {
            events("started", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

tasks.shadowJar {
    archiveClassifier.set("")

    // Relocalizado para no chocar con otros plugins que carguen las mismas
    // bibliotecas en el mismo servidor. Ver ARCHITECTURE.md, seccion 1.
    listOf(
        "net.dv8tion.jda",
        "com.zaxxer.hikari",
        "com.iwebpp.crypto",
        "com.neovisionaries.ws",
        "gnu.trove",
        "okhttp3",
        "okio",
        "kotlin",
        "org.jetbrains.annotations",
        "org.intellij.lang.annotations",
        "com.google",
        "google.protobuf",
        "org.apache.commons.collections4",
        "com.fasterxml.jackson",
        "org.slf4j",
        // org.sqlite is NOT relocated: META-INF/services/java.sql.Driver names the
        // driver class as text, relocation does not rewrite it, and the driver then
        // registers a class that no longer exists. The pool fails to open at all.
    ).forEach { relocate(it, "com.discordtowny.lib.$it") }

    // Sin minimize: JDA carga clases por reflexion y el recorte las elimina.
    // El fallo aparece en runtime, no al compilar. No merece los megabytes.
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    val values = mapOf("version" to project.version.toString())
    inputs.properties(values)
    filesMatching("paper-plugin.yml") {
        expand(values)
    }
}

// A JDBC driver registers itself through META-INF/services/java.sql.Driver, which
// names its class as plain text. Relocating the driver rewrites the class but not
// that text, so the driver registers a class that no longer exists and the pool
// never opens. Unit tests cannot see this: they use the driver from the classpath
// and never open the shaded jar. Only running the real jar showed it.
val verifyJdbcDriverIsResolvable by tasks.registering {
    dependsOn(tasks.shadowJar)
    doLast {
        val jar = tasks.shadowJar.get().archiveFile.get().asFile
        ZipFile(jar).use { zip ->
            val service = zip.getEntry("META-INF/services/java.sql.Driver")
                ?: throw GradleException("The shaded jar declares no JDBC driver service.")
            val declared = zip.getInputStream(service).bufferedReader().readText()
                .lines().map { it.substringBefore('#').trim() }.first { it.isNotEmpty() }
            if (zip.getEntry(declared.replace('.', '/') + ".class") == null) {
                throw GradleException(
                    "The JDBC driver service names " + declared + ", which is not in the jar. " +
                    "It is almost certainly relocated, and a relocated driver cannot register itself."
                )
            }
            logger.lifecycle("JDBC driver service resolves: " + declared)
        }
    }
}

tasks.build {
    dependsOn(verifyJdbcDriverIsResolvable)
}
