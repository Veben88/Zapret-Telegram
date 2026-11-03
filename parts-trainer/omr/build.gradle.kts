plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":core"))
    // Один и тот же Java API: на JVM (тесты) — onnxruntime, на Android — onnxruntime-android.
    compileOnly(libs.onnxruntime)
    testImplementation(libs.onnxruntime)
    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
    testImplementation(libs.xmlpull)
}

tasks.test {
    useJUnit()
    maxHeapSize = "3g"
    // Путь к моделям homr для проверочных тестов (иначе они пропускаются)
    environment("HOMR_MODELS", System.getenv("HOMR_MODELS") ?: "")
}
