import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy

plugins {
    id("com.gradleup.shadow")
}

val paperApiVersion = providers.gradleProperty("paperApiVersion").get()
val pluginVersion = providers.gradleProperty("version").get()

dependencies {
    implementation(project(":warsim-api"))
    implementation(project(":warsim-common"))
    implementation(project(":warsim-network"))
    implementation(project(":warsim-database"))
    implementation(project(":warsim-resourcepack"))
    implementation(project(":warsim-squad"))
    implementation(project(":warsim-classes"))
    implementation(project(":warsim-vehicles"))
    implementation(project(":warsim-destruction"))
    implementation(project(":warsim-progression"))
    implementation(project(":warsim-cosmetics"))
    implementation(project(":warsim-rental"))
    implementation(project(":warsim-anticheat"))
    implementation(project(":warsim-admin"))
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
}

tasks.processResources {
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("warsim-frontline-paper")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    append("META-INF/io.netty.versions.properties")
    filesMatching("plugin.yml") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    exclude(
        "META-INF/*.SF",
        "META-INF/*.RSA",
        "META-INF/*.DSA",
        "META-INF/LICENSE",
        "META-INF/LICENSE.txt",
        "META-INF/NOTICE",
        "META-INF/NOTICE.txt",
        "org/slf4j/**"
    )
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
