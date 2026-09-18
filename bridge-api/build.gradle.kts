plugins {
    `java-library`
    `maven-publish`
}

group = rootProject.group
version = rootProject.version

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.123-stable")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withJavadocJar()
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE.md")) {
        into("META-INF")
    }
    from(rootProject.file("NOTICE")) {
        into("META-INF")
    }
}

publishing {
    publications {
        create<MavenPublication>("bridgeApi") {
            from(components["java"])
            artifactId = "bridge-api"
            pom {
                name = "Bridge API"
                description = "Compile-time action and event provider API for the Bridge Paper plugin"
                licenses {
                    license {
                        name = "Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        distribution = "repo"
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "release"
            url = rootProject.layout.buildDirectory.dir("release-maven").get().asFile.toURI()
        }
    }
}
