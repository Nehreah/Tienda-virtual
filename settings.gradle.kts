pluginManagement {
    repositories {
        maven {
            url = java.net.URI("file:///home/nehreah/.gradle/caches/modules-2/files-2.1/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven {
            url = java.net.URI("file:///home/nehreah/.gradle/caches/modules-2/files-2.1/")
        }
        maven {
            url = java.net.URI("file:///home/nehreah/.m2/repository/")
        }
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
