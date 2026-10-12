// Throwaway spike: measures an in-place worktree swap (content root + VCS mapping) inside one open project.
//
//   ./gradlew runIde -PspikeProject=/path/to/main-checkout -PspikeTarget=/path/to/other-worktree \
//                    -PspikeReport=/path/to/report.md [-PspikeAutorun=true] [-PspikeExit=true]
//
// With spikeAutorun the experiment starts once the project is open; without it, use
// "Worktree Spike: Run Experiment" or "Worktree Spike: Swap to Worktree…" from Find Action.
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.sanex.wtspike"
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

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("ideLocalPath").getOrElse("/Users/sanex/Applications/WebStorm.app"))
        bundledPlugin("Git4Idea")
        bundledPlugin("JavaScript")
        // Split platform modules Git4Idea's API exposes (GitRepository's supertypes).
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledModule("intellij.platform.vcs.impl")
        // git4idea.GitWorkingTree lives in Git4Idea's shared content module.
        bundledModule("intellij.vcs.git.shared")
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
    providers.gradleProperty("spikeProject").orNull?.let { args(it) }
    jvmArgs(
        "-Didea.trust.all.projects=true",
        "-Djb.consents.confirmation.enabled=false",
        "-Djb.privacy.policy.text=<!--999.999-->",
        "-Dide.show.tips.on.startup.default.value=false",
    )
    mapOf(
        "spikeTarget" to "wtspike.target",
        "spikeReport" to "wtspike.report",
        "spikeAutorun" to "wtspike.autorun",
        "spikeExit" to "wtspike.exitWhenDone",
    ).forEach { (gradleProperty, systemProperty) ->
        providers.gradleProperty(gradleProperty).orNull?.let { systemProperty(systemProperty, it) }
    }
}
