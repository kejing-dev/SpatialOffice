// Pure Kotlin/JVM: file formats, formulas, layout rules. No Android imports, no third-party runtime dependencies,
// so everything here is unit-tested on the JVM and runs unchanged on the headset.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    testLogging { events("failed") }
}
