plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// 纯 Kotlin/JVM,**没有 android 依赖,也不许有**。
// iOS 那边这一层是 `AgentRuntime`:只认两个协议——「一个能估 token、能流式跑一轮的模型」
// 和「一组 JSON Schema 加一个执行闭包」,工具循环和上下文预算跑在这两者之上。
// 不认识 Health Connect、不认识任何模型 SDK,所以它的测试是秒级的、不需要模拟器。
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(17)
}
