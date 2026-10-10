plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("com.automata.app.ApplicationKt")
}

dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":commerce"))
    implementation(project(":messaging"))
    implementation(project(":adapters-shopify"))
    implementation(project(":adapters-meta"))
    implementation(project(":adapters-channels"))
    implementation(project(":infra"))
    implementation(libs.exposed.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.logback.classic)
}
