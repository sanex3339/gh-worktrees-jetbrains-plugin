import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.github.sanex3339.worktreetabs"
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // Rely on JVM default methods instead of generating "call super" bridges in classes that implement
        // platform interfaces; those bridges show up as deprecated-API usages in the Plugin Verifier.
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // -PideLocalPath=/path/to/WebStorm.app builds and runs against an installed IDE instead of downloading one.
        val ideLocalPath = providers.gradleProperty("ideLocalPath").orNull
        if (ideLocalPath.isNullOrBlank()) {
            create(IntelliJPlatformType.WebStorm, "2026.2.1")
        } else {
            local(ideLocalPath)
        }
        bundledPlugin("Git4Idea")
        // Split platform modules Git4Idea's API exposes (GitRepository's supertypes).
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledModule("intellij.platform.vcs.impl")
        pluginVerifier()
        zipSigner()
    }
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    instrumentCode = false
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            val ideLocalPath = providers.gradleProperty("ideLocalPath").orNull
            if (ideLocalPath.isNullOrBlank()) recommended() else local(ideLocalPath)
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.runIde {
    // ./gradlew runIde -PrunIdeProject=/path/to/repo opens that project in the sandbox IDE.
    providers.gradleProperty("runIdeProject").orNull?.let { args(it) }
    // -PrunIdeQuiet=true skips the sandbox's first-run consent and project trust dialogs (scripted runs).
    if (providers.gradleProperty("runIdeQuiet").orNull == "true") {
        jvmArgs(
            "-Didea.trust.all.projects=true",
            "-Djb.consents.confirmation.enabled=false",
            "-Djb.privacy.policy.text=<!--999.999-->",
            "-Dide.show.tips.on.startup.default.value=false",
        )
    }
}
