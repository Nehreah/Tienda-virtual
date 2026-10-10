pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "automata-comercial-multicanal"

include(":shared-kernel")
include(":commerce")
include(":messaging")
include(":adapters-shopify")
include(":adapters-meta")
include(":adapters-channels")
include(":infra")
include(":app")
