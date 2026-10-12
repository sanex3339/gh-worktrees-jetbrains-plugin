// End-to-end harness for Worktree Tabs: a dev-only plugin that runs next to Worktree Tabs in a sandbox IDE, drives
// real switches between two worktrees and writes a pass/fail report. Never published.
//
//   (cd .. && ./gradlew buildPlugin -PideLocalPath=…)    # the plugin under test
//   ./gradlew runIde -Pmain=/path/to/main-checkout -Pworktree=/path/to/linked-worktree -Preport=/path/to/report.md
//
// The main checkout needs `wtspike/use.ts` and `wtspike/marker.ts`, the worktree the same files; the harness edits
// `wtspike/use.ts` in the main checkout and puts it back at the start of the next run.
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.github.sanex3339.worktreetabs.e2e"
version = "0.0.1"

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val pluginUnderTest = rootDir.resolve("../build/distributions").listFiles { file -> file.name.startsWith("worktree-tabs-") }
    ?.maxByOrNull { it.lastModified() } ?: error("Build the plugin first: (cd .. && ./gradlew buildPlugin)")

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("ideLocalPath").getOrElse("/Users/sanex/Applications/WebStorm.app"))
        localPlugin(pluginUnderTest)
        bundledPlugin("Git4Idea")
        bundledPlugin("JavaScript")
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledModule("intellij.platform.vcs.impl")
        bundledModule("intellij.platform.bookmarks")
    }
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
}

tasks.runIde {
    providers.gradleProperty("main").orNull?.let { args(it) }
    jvmArgs(
        "-Didea.trust.all.projects=true",
        "-Djb.consents.confirmation.enabled=false",
        "-Djb.privacy.policy.text=<!--999.999-->",
        "-Dide.show.tips.on.startup.default.value=false",
    )
    systemProperty("e2e.autorun", "true")
    providers.gradleProperty("worktree").orNull?.let { systemProperty("e2e.worktree", it) }
    providers.gradleProperty("report").orNull?.let { systemProperty("e2e.report", it) }
    systemProperty("e2e.exit", providers.gradleProperty("exit").getOrElse("true"))
}
