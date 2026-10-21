plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.wbhub.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wbhub.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 63
        versionName = "0.6.3"
    }

    /**
     * 固定签名。
     *
     * 之前 debug 包用的是构建机自动生成的临时 keystore，CI 每次跑都是新的，导致
     * 「软件包与现有软件包存在冲突」（签名不一致）而无法覆盖安装，应用内自动更新也失效。
     * 这个 keystore 提交在仓库里，debug 与 release 共用，保证同一包名始终同一签名。
     *
     * 只用于自用分发自签，不构成任何信任背书；密码随仓库公开。
     */
    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("keystore/wbhub.jks")
            storePassword = "wbhub-local"
            keyAlias = "wbhub"
            keyPassword = "wbhub-local"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("stable")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
}
