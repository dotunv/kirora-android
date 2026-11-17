plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.google.devtools.ksp)
    `maven-publish`
    signing
}

// Single source for the version: the Maven artifact, the Kirora-SDK header and event context.
val sdkVersion = providers.gradleProperty("kiroraSdkVersion").get()

kotlin {
    explicitApi()
    compilerOptions {
        // The Kirora artifacts use each other's @KiroraInternalApi hooks; apps cannot.
        freeCompilerArgs.add("-opt-in=com.kirora.androidsdk.KiroraInternalApi")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "io.kirora.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "consumer-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "com.kirora"
                artifactId = "android-sdk"
                version = sdkVersion

                from(components["release"])

                pom {
                    name.set("Kirora Android SDK")
                    description.set("Track events, identify users and receive push notifications with Kirora.")
                    url.set("https://www.kirora.io")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("kirora")
                            name.set("Kirora")
                            url.set("https://www.kirora.io")
                        }
                    }
                    scm {
                        url.set("https://github.com/kiroratech/kirora-android")
                        connection.set("scm:git:https://github.com/kiroratech/kirora-android.git")
                        developerConnection.set("scm:git:ssh://git@github.com/kiroratech/kirora-android.git")
                    }
                }
            }
        }
        repositories {
            // Local staging repo: `./gradlew :kirora-sdk:publishReleasePublicationToStagingRepository`
            maven {
                name = "staging"
                url = uri(layout.buildDirectory.dir("staging-repo"))
            }
        }
    }
}

// Signs only when a key is configured (ORG_GRADLE_PROJECT_signingInMemoryKey / ...Password).
signing {
    val key = providers.gradleProperty("signingInMemoryKey").orNull
    if (key != null) {
        useInMemoryPgpKeys(key, providers.gradleProperty("signingInMemoryKeyPassword").orNull)
        sign(publishing.publications)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.gson)
    implementation(libs.okhttp)
    // Part of the public API (`Flow`, `suspend`), so consumers need it on their compile classpath.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
