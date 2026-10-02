plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":messaging"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
}
