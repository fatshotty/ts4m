plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.badoualy:kotlogram:1.0.0-RC3")
    implementation("org.slf4j:slf4j-simple:1.7.36")
}

application {
    mainClass.set("SpikeKt")
}
