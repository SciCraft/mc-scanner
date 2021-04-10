rootProject.name = "mc-scanner"

include("libdeflate-java:libdeflate-java-core")

pluginManagement {
    repositories {
        maven("https://dl.bintray.com/kotlin/kotlinx")
        maven("https://dl.bintray.com/kotlin/kotlin-eap")
        gradlePluginPortal()
        mavenCentral()
    }
}