dependencies {
    api(project(":warsim-api"))
    implementation("io.lettuce:lettuce-core:${providers.gradleProperty("lettuceVersion").get()}")
}

val redisIntegrationTest = sourceSets.create("redisIntegrationTest")

configurations[redisIntegrationTest.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get()
)
configurations[redisIntegrationTest.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get()
)

dependencies {
    add(redisIntegrationTest.implementationConfigurationName, project())
    add(
        redisIntegrationTest.implementationConfigurationName,
        "org.testcontainers:testcontainers:${providers.gradleProperty("testcontainersVersion").get()}"
    )
}

tasks.register<Test>("redisIntegrationTest") {
    description = "Runs Redis integration tests with Testcontainers."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = redisIntegrationTest.output.classesDirs
    classpath = redisIntegrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    useJUnitPlatform()
}
