import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // XmlPullParser is part of the Android platform; on the JVM (tests) kxml2 provides it.
    compileOnly("xmlpull:xmlpull:1.1.3.1")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Hits the real FAA endpoints and prints what it finds. Run in CI (not part of `test`)
// to confirm URL patterns and file formats still match what the app expects.
tasks.register<JavaExec>("liveCheck") {
    group = "verification"
    description = "Checks live FAA data sources (network required)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.simpleefb.core.LiveCheckKt")
}
