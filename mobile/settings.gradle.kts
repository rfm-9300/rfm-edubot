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
include(":core:data")
include(":core:localization")
include(":core:model")
include(":core:network")
include(":core:testing")
include(":core:ui")
include(":feature:agents")
include(":feature:assistant")
include(":feature:auth")
include(":feature:bookings")
include(":feature:contacts")
include(":feature:crm")
include(":feature:inbox")
include(":feature:notifications")
include(":feature:overview")
include(":feature:persona")
include(":feature:settings")
include(":feature:timeclock")
include(":shared")
include(":androidApp")
