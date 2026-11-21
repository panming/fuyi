import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

data class ReleaseSigning(
    val storeFile: java.io.File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun loadReleaseSigning(): ReleaseSigning? {
    val configured = System.getenv("FUYI_SIGNING_PROPERTIES")?.trim().orEmpty()
    val values = linkedMapOf<String, String>()
    if (configured.isNotEmpty()) {
        val propsFile = file(configured)
        if (!propsFile.isFile) {
            throw GradleException(
                "FUYI_SIGNING_PROPERTIES does not point to a file. Release builds require that external file and will not use the debug keystore.",
            )
        }
        val props = Properties()
        propsFile.inputStream().use { props.load(it) }
        val storeName = props.getProperty("storeFile")?.trim().orEmpty()
        val store = when {
            storeName.isEmpty() -> null
            file(storeName).isAbsolute -> file(storeName)
            else -> file(propsFile.parentFile.resolve(storeName).absolutePath)
        }
        values["storeFile"] = store?.absolutePath.orEmpty()
        values["storePassword"] = props.getProperty("storePassword").orEmpty()
        values["keyAlias"] = props.getProperty("keyAlias")?.trim().orEmpty()
        values["keyPassword"] = props.getProperty("keyPassword").orEmpty()
    } else {
        values["storeFile"] = System.getenv("FUYI_STORE_FILE")?.trim().orEmpty()
        values["storePassword"] = System.getenv("FUYI_STORE_PASSWORD").orEmpty()
        values["keyAlias"] = System.getenv("FUYI_KEY_ALIAS")?.trim().orEmpty()
        values["keyPassword"] = System.getenv("FUYI_KEY_PASSWORD").orEmpty()
        if (values.values.all { it.isEmpty() }) return null
    }
    val storeFile = values["storeFile"].orEmpty().let { if (it.isEmpty()) null else file(it) }
    val ready = storeFile != null && storeFile.isFile &&
        values["storePassword"].orEmpty().isNotEmpty() &&
        values["keyAlias"].orEmpty().isNotEmpty() &&
        values["keyPassword"].orEmpty().isNotEmpty()
    if (!ready) {
        throw GradleException(
            "Release signing is incomplete. Provide storeFile, storePassword, keyAlias and keyPassword through FUYI_SIGNING_PROPERTIES or FUYI_STORE_FILE, FUYI_STORE_PASSWORD, FUYI_KEY_ALIAS and FUYI_KEY_PASSWORD. The debug keystore will not be used.",
        )
    }
    return ReleaseSigning(
        storeFile = storeFile!!,
        storePassword = values.getValue("storePassword"),
        keyAlias = values.getValue("keyAlias"),
        keyPassword = values.getValue("keyPassword"),
    )
}

val releaseSigning = loadReleaseSigning()

android {
    namespace = "com.fanyi.overlay"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.fanyi.overlay"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = releaseSigning.storeFile
                storePassword = releaseSigning.storePassword
                keyAlias = releaseSigning.keyAlias
                keyPassword = releaseSigning.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.savedstate.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.security.crypto)
    implementation(libs.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}

gradle.taskGraph.whenReady {
    val assemblingRelease = allTasks.any { it.project == project && it.name == "assembleRelease" }
    if (assemblingRelease && releaseSigning == null) {
        throw GradleException(
            "assembleRelease requires signing outside the repository. Set FUYI_SIGNING_PROPERTIES, or set FUYI_STORE_FILE, FUYI_STORE_PASSWORD, FUYI_KEY_ALIAS and FUYI_KEY_PASSWORD. Refusing to sign a release with the debug certificate.",
        )
    }
}
