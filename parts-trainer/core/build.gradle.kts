plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(libs.kotlinx.serialization.json)
    // API XmlPullParser входит в платформу Android, поэтому только compileOnly.
    compileOnly(libs.xmlpull)
    testImplementation(libs.xmlpull)
    testImplementation(libs.kxml2)
    testImplementation(libs.junit)
}

tasks.test { useJUnit() }
