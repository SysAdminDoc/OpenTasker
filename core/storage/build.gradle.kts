plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // Required now that this module actually compiles the entities: without it the @Serializable
    // classes get no generated serializer and every profile write fails at runtime.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.opentasker.core.storage"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$rootDir/app/schemas")
    arg("room.incremental", "true")
}

dependencies {
    api(project(":core:model"))
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.runtime)
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.sqlcipher.android)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    // The Compose compiler plugin runs over the unit-test source set too and refuses to compile
    // without the runtime on the class path, even though nothing here is a composable.
    testCompileOnly(platform(libs.androidx.compose.bom))
    testCompileOnly(libs.androidx.compose.runtime)
    implementation(libs.work.runtime)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
}
