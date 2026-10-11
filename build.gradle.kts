plugins {
    alias(libs.plugins.fabric.loom)
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

base {
    archivesName.set("irisrt")
}

repositories {
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
        content { includeGroup("maven.modrinth") }
    }
}

dependencies {
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)

    // Minecraft 26.3 and this Iris JAR use official names; no remapping is needed.
    implementation(files("thirdparty/iris-vk-${libs.versions.iris.get()}.jar"))
    runtimeOnly(libs.sodium)
    // Iris embeds this library; expose its AST API for our shader resource extension.
    compileOnly(libs.glsl.transformer)
    testImplementation(libs.glsl.transformer)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Opt-in integration mod: exercises real GPU commands and exits, never packaged in IrisRT.
if (providers.gradleProperty("asSmokeTest").isPresent) {
    val smoke = sourceSets.create("smoke") {
        compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
        runtimeClasspath += sourceSets.main.get().runtimeClasspath
    }
    val graphicsBackend = providers.gradleProperty("smokeBackend").getOrElse("VULKAN")
    loom {
        mods {
            create("irisrt") { sourceSet(sourceSets.main.get()) }
            create("irisrt_smoke") { sourceSet(smoke) }
        }
        runs.named("client") {
            sourceSet.set(smoke.name)
            programArguments.addAll("--graphicsBackend", graphicsBackend)
            if (graphicsBackend == "VULKAN") programArguments.add("--vulkanValidation")
            systemProperties.put("irisrt.smokeBackend", graphicsBackend)
            runDirectory.set(layout.buildDirectory.dir("smoke-run-${graphicsBackend.lowercase()}"))
        }
    }
}

tasks.processResources {
    val properties = mapOf(
        "version" to project.version.toString(),
        "minecraft_version" to libs.versions.minecraft.get(),
        "loader_version" to libs.versions.fabric.loader.get(),
        "iris_version" to libs.versions.iris.get(),
    )
    inputs.properties(properties)
    filesMatching("fabric.mod.json") {
        expand(properties)
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.jar {
    from("LICENSE") {
        rename { "LICENSE_irisrt" }
    }
}
