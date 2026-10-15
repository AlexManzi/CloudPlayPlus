import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.gxcloud"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.gxcloud"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.2.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

abstract class MinifyInjectTask @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceScript: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val minifierScript: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val toolManifests: ConfigurableFileCollection

    @get:Input
    abstract val nodeExecutable: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        execOperations.exec {
            commandLine(
                nodeExecutable.get(), minifierScript.get().asFile.absolutePath,
                sourceScript.get().asFile.absolutePath,
                outputDirectory.file("raw/gxcloud_inject.js").get().asFile.absolutePath
            )
        }
    }
}

// The release variant overlays the readable main resource with generated output.
// Debug keeps the original script. Install pinned build tools with `npm ci` first.
val localNodeExecutable = providers.fileContents(
    rootProject.layout.projectDirectory.file("local.properties")
).asText.map { contents ->
    Properties().apply { load(contents.reader()) }
        .getProperty("nodeExecutable", "node")
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val minifyInject = tasks.register<MinifyInjectTask>("minifyReleaseInject") {
            sourceScript.set(layout.projectDirectory.file("src/main/res/raw/gxcloud_inject.js"))
            minifierScript.set(rootProject.layout.projectDirectory.file("tools/minify-inject.cjs"))
            toolManifests.from(
                rootProject.file("package.json"), rootProject.file("package-lock.json")
            )
            nodeExecutable.set(
                providers.gradleProperty("nodeExecutable").orElse(localNodeExecutable).orElse("node")
            )
            outputDirectory.set(layout.buildDirectory.dir("generated/minifiedInject/release"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(minifyInject, MinifyInjectTask::outputDirectory)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
