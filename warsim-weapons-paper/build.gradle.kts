import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy

plugins {
    id("com.gradleup.shadow")
}

val paperApiVersion = providers.gradleProperty("paperApiVersion").get()
val craftEngineVersion = providers.gradleProperty("craftEngineVersion").get()
val pluginVersion = providers.gradleProperty("version").get()

dependencies {
    implementation(project(":warsim-weapons")) {
        isTransitive = false
    }
    compileOnly(project(":warsim-api"))
    compileOnly(project(":warsim-admin"))
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    compileOnly("net.momirealms:craft-engine-core:$craftEngineVersion")
    compileOnly("net.momirealms:craft-engine-bukkit:$craftEngineVersion")
}

tasks.processResources {
    inputs.property("version", pluginVersion)
    filesMatching("paper-plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("warsim-frontline-weapons")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude(
        "com/warsim/frontline/api/**",
        "com/warsim/frontline/admin/**",
        "org/bukkit/**",
        "io/papermc/**",
        "net/momirealms/**",
        "org/junit/**",
        "META-INF/*.SF",
        "META-INF/*.RSA",
        "META-INF/*.DSA"
    )
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
