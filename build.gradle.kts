plugins {
    java
    // Add the Shadow plugin if you want to shade dependencies (not required here)
}

group = "gg.auroramc.potionaddon"
version = "1.0.0"

java {
    // Target Java 17+ since Minecraft 1.21.4 runs on this
    toolchain.languageVersion.set(JavaLanguageVersion.of(24))
}

repositories {
    mavenCentral()
    // AuroraMC and Paper repositories are required to compile against their APIs
    maven("https://repo.auroramc.gg/releases/")
    maven("https://repo.papermc.io/repository/maven-public/")
    flatDir {
        dirs("lib")
    }
}

dependencies {
    // Provide-only dependencies; they are not bundled into the final jar
    compileOnly(files("lib/AuroraQuests-2.2.0.jar"))
    compileOnly(files("lib/Aurora-2.5.1.jar"))
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

tasks.jar {
    // Do not include any dependencies in the jar; AuroraQuests and Paper will be present at runtime
    from(sourceSets.main.get().output)
    archiveFileName.set("AuroraPotionConsumeAddon-1.0.0.jar")
}
