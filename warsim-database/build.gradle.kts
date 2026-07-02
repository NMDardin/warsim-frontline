dependencies {
    api(project(":warsim-api"))
    implementation("com.zaxxer:HikariCP:${providers.gradleProperty("hikariVersion").get()}")
    implementation("org.postgresql:postgresql:${providers.gradleProperty("postgresqlVersion").get()}")
    implementation("org.flywaydb:flyway-core:${providers.gradleProperty("flywayVersion").get()}")
    implementation("org.flywaydb:flyway-database-postgresql:${providers.gradleProperty("flywayVersion").get()}")
}

val integrationTestSourceSet = sourceSets.create("integrationTest")

configurations[integrationTestSourceSet.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get()
)
configurations[integrationTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get()
)

dependencies {
    add(integrationTestSourceSet.implementationConfigurationName, project())
    add(
        integrationTestSourceSet.implementationConfigurationName,
        "org.testcontainers:testcontainers-postgresql:${providers.gradleProperty("testcontainersVersion").get()}"
    )
}

tasks.register<Test>("integrationTest") {
    description = "Runs PostgreSQL integration tests with Testcontainers."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
    useJUnitPlatform()
}
