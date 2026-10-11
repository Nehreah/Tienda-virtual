plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
}

allprojects {
    group = "com.automata"
    version = "5.3.0"
}

// Única fuente de verdad de la versión de Java. Debe coincidir con el Dockerfile (eclipse-temurin:21)
// y con .github/workflows/ci.yml (java-version: 21). Si la cambias, cambia también esos dos archivos.
val javaVersion = 21

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(javaVersion)
    }

    val libsCatalog = rootProject.extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("libs")

    dependencies {
        "testImplementation"(libsCatalog.findLibrary("junit-jupiter-api").get())
        "testRuntimeOnly"(libsCatalog.findLibrary("junit-jupiter-engine").get())
        "testRuntimeOnly"(libsCatalog.findLibrary("junit-platform-launcher").get())
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
