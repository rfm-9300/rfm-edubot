pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "edubot-mobile"

include(":core:common")
include(":core:localization")
include(":core:model")
include(":core:network")
include(":core:testing")
include(":core:ui")
include(":feature:assistant")
include(":feature:auth")
include(":feature:contacts")
include(":feature:crm")
include(":feature:inbox")
include(":feature:overview")
include(":feature:persona")
include(":feature:settings")
include(":shared")
include(":androidApp")
