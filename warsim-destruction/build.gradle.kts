dependencies {
    api(project(":warsim-api"))
    compileOnly("io.papermc.paper:paper-api:${providers.gradleProperty("paperApiVersion").get()}")
}
