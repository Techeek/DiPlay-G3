import org.gradle.api.tasks.Classpath
import org.gradle.process.CommandLineArgumentProvider

abstract class MockitoAgentArguments : CommandLineArgumentProvider {
    @get:Classpath
    abstract val agentFiles: ConfigurableFileCollection
    override fun asArguments(): Iterable<String> = listOf("-javaagent:${agentFiles.singleFile.absolutePath}")
}

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

val mockitoAgent = configurations.create("mockitoAgent")

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 25
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The UI suite covers several SDKs and locale-specific resource sandboxes.
        unitTests.all {
            it.maxHeapSize = "1g"
            // Stable test-host locale, matching CI; per-test qualifiers still cover Chinese/RTL.
            it.systemProperty("user.language", "en")
            it.systemProperty("user.country", "US")
            it.jvmArgumentProviders.add(objects.newInstance(MockitoAgentArguments::class.java).apply {
                agentFiles.from(mockitoAgent)
            })
        }
    }
}

dependencies {
    api(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.mockito:mockito-core:5.20.0")
    add("mockitoAgent", "org.mockito:mockito-core:5.20.0") { isTransitive = false }
    testImplementation(libs.jmdns)
}
