plugins {
    alias(deps.plugins.kotlin.jvm)
    id("configuration")
}

dependencies {

    // region Kotlin
    implementation(deps.kotlin.coroutines)
    implementation(deps.kotlin.serialization)
    // endregion

    // region Other libraries
    implementation(deps.bouncycastle.bcprov)
    implementation(deps.okHttp)
    implementation(deps.ton.kotlin.tvm)
    // endregion

    // region Core modules
    implementation(projects.core.utils)
    // endregion

    // region Tests
    testImplementation(deps.test.coroutine)
    testImplementation(deps.test.junit5)
    testImplementation(deps.test.truth)
    testImplementation(projects.test.core)
    // endregion
}
