import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Zip
import java.security.MessageDigest

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.playcity"
val effectiveBridgeVersion = providers.gradleProperty("bridgeVersion").orElse("0.5.0-SNAPSHOT").get()
version = effectiveBridgeVersion

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.111-stable")
    implementation(project(":bridge-api"))
    implementation("com.google.code.gson:gson:2.14.0") {
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    implementation("org.java-websocket:Java-WebSocket:1.6.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    testImplementation("io.papermc.paper:paper-api:26.2.build.111-stable")
    testImplementation(platform("org.junit:junit-bom:6.1.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to effectiveBridgeVersion)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.java_websocket", "com.playcity.bridge.lib.websocket")
    relocate("com.nimbusds", "com.playcity.bridge.lib.nimbus")
    from("LICENSE.md") {
        into("META-INF")
    }
    from("NOTICE") {
        into("META-INF")
    }
    from("THIRD_PARTY_NOTICES.md") {
        into("META-INF")
    }
    from("LICENSES/Apache-2.0.txt") {
        into("META-INF/LICENSES")
    }
    from("LICENSES/MIT-Java-WebSocket.txt") {
        into("META-INF/LICENSES")
    }
}

tasks.jar {
    enabled = false
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.register("printVersion") {
    group = "help"
    description = "Prints the effective Bridge version for automation."
    doLast {
        println(effectiveBridgeVersion)
    }
}

val releaseBundle = tasks.register<Zip>("releaseBundle") {
    group = "distribution"
    description = "Builds the complete Bridge binary and developer release bundle."
    dependsOn(tasks.build, ":bridge-api:publishBridgeApiPublicationToReleaseRepository")
    archiveFileName.set("Bridge-$effectiveBridgeVersion-release.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    from(tasks.shadowJar.flatMap { it.archiveFile }) {
        into("plugin")
    }
    from(project(":bridge-api").layout.buildDirectory.dir("libs")) {
        include("bridge-api-$effectiveBridgeVersion*.jar")
        into("api")
    }
    from(layout.buildDirectory.dir("release-maven")) {
        into("maven")
    }
    from("README.md", "CHANGELOG.md", "CONTRIBUTING.md", "SECURITY.md", "LICENSE.md", "NOTICE", "THIRD_PARTY_NOTICES.md")
    from("LICENSES") {
        into("LICENSES")
    }
    from("docs") {
        include(
            "COMPATIBILITY.md",
            "DEVELOPER-API.md",
            "FUTURE-AUTH-REALTIME.md",
            "HTTP-API.md",
            "JWT-AUTH.md",
            "LILO-INTEGRATION-STUDY.md",
            "OBSERVABILITY.md",
            "PLAYER-STATUS.md",
            "QUICKSTART.md",
            "RELAY-PROTOCOL.md",
            "RELEASE-CHECKLIST.md",
            "WEBSOCKET-EVENTS.md",
            "openapi.yaml"
        )
        into("docs")
    }
}

val stageReleaseArtifacts = tasks.register<Sync>("stageReleaseArtifacts") {
    group = "distribution"
    description = "Stages release JARs and the release bundle in one directory."
    dependsOn(tasks.build, releaseBundle)
    into(layout.buildDirectory.dir("release"))
    from(tasks.shadowJar.flatMap { it.archiveFile })
    from(project(":bridge-api").layout.buildDirectory.dir("libs")) {
        include("bridge-api-$effectiveBridgeVersion*.jar")
    }
    from(releaseBundle.flatMap { it.archiveFile })
}

val writeReleaseChecksums = tasks.register("writeReleaseChecksums") {
    group = "distribution"
    description = "Writes SHA-256 checksums for every staged release artifact."
    dependsOn(stageReleaseArtifacts)
    val releaseDirectory = layout.buildDirectory.dir("release")
    inputs.dir(releaseDirectory)
    outputs.file(releaseDirectory.map { it.file("SHA256SUMS") })
    doLast {
        val directory = releaseDirectory.get().asFile
        val artifacts = directory.listFiles()
            ?.filter { it.isFile && it.name != "SHA256SUMS" }
            ?.sortedBy { it.name }
            ?: emptyList()
        check(artifacts.isNotEmpty()) { "No Bridge release artifacts were staged." }
        val lines = artifacts.map { artifact ->
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } + "  " + artifact.name
        }
        directory.resolve("SHA256SUMS").writeText(lines.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
    }
}

val verifyReleaseReadiness = tasks.register("verifyReleaseReadiness") {
    group = "verification"
    description = "Refuses a public release with snapshot, changelog, license, notice, or POM defects."
    dependsOn(tasks.build)
    doLast {
        val releaseVersion = effectiveBridgeVersion
        check(releaseVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?"))
            && !releaseVersion.endsWith("-SNAPSHOT", ignoreCase = true)) {
            "Release version must be semantic and must not contain SNAPSHOT: $releaseVersion"
        }
        val changelog = file("CHANGELOG.md").readText()
        check(Regex("(?m)^## ${Regex.escape(releaseVersion)} - [0-9]{4}-[0-9]{2}-[0-9]{2}$").containsMatchIn(changelog)) {
            "CHANGELOG.md must contain a dated release heading for $releaseVersion."
        }
        val license = file("LICENSE.md").readText()
        check(license.contains("Apache License")
            && license.contains("Version 2.0, January 2004")
            && license.contains("Grant of Patent License")) {
            "LICENSE.md must contain the complete Apache License 2.0 text."
        }
        val notice = file("NOTICE")
        check(notice.isFile && notice.readText().contains("PLAYCITY project contributors")) {
            "NOTICE must contain the Bridge attribution."
        }
        val apiBuild = file("bridge-api/build.gradle.kts").readText()
        check(apiBuild.contains("Apache License, Version 2.0")
            && apiBuild.contains("https://www.apache.org/licenses/LICENSE-2.0.txt")) {
            "The bridge-api POM must declare Apache License 2.0."
        }
    }
}

tasks.register("assembleRelease") {
    group = "distribution"
    description = "Runs release gates and produces checksummed release artifacts."
    dependsOn(verifyReleaseReadiness, writeReleaseChecksums)
}
