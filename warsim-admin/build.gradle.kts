val paperApiVersion = providers.gradleProperty("paperApiVersion").get()

dependencies {
    api(project(":warsim-api"))
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
}
