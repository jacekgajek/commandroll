plugins {
    kotlin("jvm") version "1.8.21"
    application
}

group = "io.github.jacekgajek"
version = "0.1.1"

repositories {
    mavenCentral()
}

dependencies {
    val kotestVersion = "5.6.2"

    implementation("io.github.microutils:kotlin-logging-jvm:2.1.21")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(11)
}

application {
    mainClass.set("MainKt")
}