import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

val webDist = rootProject.projectDir.parentFile.resolve("packages/jetbrains-web/dist").absoluteFile
val generatedResources = layout.buildDirectory.dir("generated/resources")

sourceSets {
    main {
        resources.srcDir(generatedResources)
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2025.3.1")
        bundledPlugin("com.intellij.modules.jcef")
        testFramework(TestFrameworkType.Platform)
    }
}

tasks.register<Sync>("syncWebBundle") {
    doFirst {
        require(webDist.resolve("marktext-web.js").isFile && webDist.resolve("marktext-web.css").isFile) {
            "Missing JetBrains Web bundle at $webDist. Run pnpm --filter @marktext/jetbrains-web build first."
        }
    }
    from(webDist) {
        include("marktext-web.js")
        include("marktext-web.css")
    }
    into(generatedResources.map { it.dir("web") })
}

tasks.matching { it.name == "processResources" }.configureEach {
    dependsOn("syncWebBundle")
}

tasks.matching { it.name == "prepareSandbox" || it.name == "buildPlugin" }.configureEach {
    dependsOn("syncWebBundle")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}
