import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import kotlinx.benchmark.gradle.JvmBenchmarkTarget

plugins {
    kotlin("jvm") version "1.4.21"
    id("kotlinx.benchmark") version "0.2.0-dev-20"
    id("org.jetbrains.kotlin.plugin.allopen") version "1.4.20"
    id("com.github.johnrengelman.shadow") version "6.1.0"
}


repositories {
    mavenCentral()
    maven("https://dl.bintray.com/kotlin/kotlinx")
}

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("it.unimi.dsi:fastutil:8.5.2")
    implementation("net.sf.jopt-simple:jopt-simple:6.0-alpha-3")
    implementation("org.jetbrains.kotlinx:kotlinx.benchmark.runtime-jvm:0.2.0-dev-20")
    implementation(project(":libdeflate-java:libdeflate-java-core"))

    testImplementation("org.jetbrains.kotlin:kotlin-test")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}

tasks {
    named<ShadowJar>("shadowJar") {
        classifier = ""
        mergeServiceFiles()
        minimize()
        manifest {
            attributes(mapOf("Main-Class" to "de.skyrising.mc.scanner.ScannerKt"))
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

benchmark {
    targets {
        register("main") {
            (this as JvmBenchmarkTarget).jmhVersion = "1.28"
        }
    }
}