plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "dev.aircast"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.aircast"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Motorola edge 40 (and nearly every current phone) is arm64.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            // pyatv is installed WITHOUT its dependency resolution, because two of its
            // requirements (miniaudio, pydantic>=2 / pydantic-core) need native compilation
            // that is not available for Android. Every dependency is listed explicitly instead.
            // "miniaudio" is replaced by a tiny WAV-only shim in app/src/main/python/miniaudio.py
            options("--no-deps")

            install("pyatv==0.16.1")

            // pure-Python dependencies
            install("async-timeout")
            install("chacha20poly1305-reuseable")
            install("ifaddr")
            install("protobuf")
            install("pydantic==1.10.18")
            install("typing-extensions")
            install("requests")
            install("urllib3")
            install("idna")
            install("certifi")
            install("charset-normalizer")
            install("srptools")
            install("six")
            install("tabulate")
            install("tinytag")
            install("zeroconf")
            install("aiosignal")
            install("attrs")
            install("aiohappyeyeballs")

            // dependencies with native code (must exist in Chaquopy's wheel repository)
            install("cryptography")
            install("cffi")
            install("pycparser")
            install("aiohttp")
            install("multidict")
            install("yarl")
            install("frozenlist")
            install("propcache")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
