import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import de.skyrising.mc.scanner.gen.generateRandomTicksKt

plugins {
    kotlin("jvm") version "1.8.0"
    kotlin("plugin.serialization") version "1.8.0"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    application
}


repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("reflect"))
    implementation("it.unimi.dsi:fastutil:8.5.8")
    implementation("net.sf.jopt-simple:jopt-simple:6.0-alpha-3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")

    implementation("org.jetbrains.kotlin:kotlin-scripting-common")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm")
    //implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies")
    //implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies-maven")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host")

    testImplementation(kotlin("test"))
}

application {
    mainClassName = "de.skyrising.mc.scanner.ScannerKt"
}

tasks {
    named<ShadowJar>("shadowJar") {
        classifier = ""
        mergeServiceFiles()
        minimize {
            exclude(dependency("org.jetbrains.kotlin:.*"))
        }
        dependsOn(distTar, distZip)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

val generatedKotlinDir = project.buildDir.resolve("generated/kotlin")

tasks.create("generateSources") {
    doFirst {
        generateRandomTicksKt().writeTo(generatedKotlinDir)
    }
}

tasks.compileKotlin {
    dependsOn("generateSources")
}

sourceSets {
    main {
        java {
            srcDir(generatedKotlinDir)
        }
    }
}