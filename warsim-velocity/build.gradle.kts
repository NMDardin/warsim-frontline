import org.gradle.api.file.DuplicatesStrategy
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.gradleup.shadow")
}

val velocityApiVersion = providers.gradleProperty("velocityApiVersion").get()

dependencies {
    implementation(project(":warsim-api"))
    implementation(project(":warsim-common"))
    implementation(project(":warsim-network"))
    compileOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
    annotationProcessor("com.velocitypowered:velocity-api:$velocityApiVersion")
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("warsim-frontline-velocity")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    configurations = listOf(project.configurations.runtimeClasspath.get())
    mergeServiceFiles()
    append("META-INF/io.netty.versions.properties")
    exclude(
        "META-INF/*.SF",
        "META-INF/*.RSA",
        "META-INF/*.DSA",
        "com/warsim/frontline/api/objective/**",
        "com/warsim/frontline/api/ticket/**",
        "com/warsim/frontline/api/weapon/**",
        "com/warsim/frontline/api/battle/**",
        "org/slf4j/**"
    )
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
