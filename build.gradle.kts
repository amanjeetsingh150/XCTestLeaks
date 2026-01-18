plugins {
    application
    kotlin("jvm") version "2.1.0"
}

application {
    applicationName = "xctestleaks"
    mainClass.set("xctestleaks.AppKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("info.picocli:picocli:4.6.3")
    implementation("info.picocli:picocli-codegen:4.6.3")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}