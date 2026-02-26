pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HappyAndroidTestApp"
include(":app")

includeBuild("../HappyPlatformAPI") {
    dependencySubstitution {
        substitute(module("com.happyhealth:bleplatform")).using(project(":shared"))
    }
}
