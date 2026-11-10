plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

import io.appium.espressoserver.gradle.resolveCapabilityVersion
import io.appium.espressoserver.gradle.withCapabilityVersion
import io.appium.espressoserver.jvmtarget.AppiumJvmTarget
import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Only the Compose instrumentation fixture declares @Composable functions. Keep the compiler
// plugin off production and composeOff compilations, which have no Compose runtime dependency.
val composeTestCompiler = configurations.create("composeTestCompiler")
dependencies {
    add(composeTestCompiler.name, "org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:${libs.versions.kotlin.get()}") {
        isTransitive = false
    }
}
tasks.withType<KotlinCompile>().configureEach {
    if (name.contains("ComposeOn") && name.contains("AndroidTest")) {
        compilerOptions.freeCompilerArgs.add(composeTestCompiler.elements.map { files ->
            "-Xplugin=${files.single().asFile.absolutePath}"
        })
    }
}

val appiumCompileSdk = project.property("appiumCompileSdk") as String
val appiumMinSdk = project.property("appiumMinSdk") as String
val appiumTargetSdk = project.property("appiumTargetSdk") as String
val appiumBuildTools = project.property("appiumBuildTools") as String
val appiumSourceCompatibility = project.property("appiumSourceCompatibility") as String
val appiumTargetCompatibility = project.property("appiumTargetCompatibility") as String
val appiumJvmTarget = project.property("appiumJvmTarget") as String

android {
    compileSdk = appiumCompileSdk.toInt()
    buildToolsVersion = appiumBuildTools
    namespace = "io.appium.espressoserver.lib"

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "composeSupport"
    productFlavors {
        create("composeOn") {
            dimension = "composeSupport"
            isDefault = true
            buildConfigField("boolean", "COMPOSE_SUPPORT", "true")
        }
        create("composeOff") {
            dimension = "composeSupport"
            buildConfigField("boolean", "COMPOSE_SUPPORT", "false")
        }
    }

    defaultConfig {
        minSdk = appiumMinSdk.toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.valueOf(appiumSourceCompatibility.uppercase())
        targetCompatibility = JavaVersion.valueOf(appiumTargetCompatibility.uppercase())
    }

    packaging {
        // Keep third-party license/notice files when combining dependency archives.
        resources.excludes.addAll(listOf("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/INDEX.LIST"))
        resources.merges.addAll(listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/AL2.0", "META-INF/LGPL2.1"))
    }

    lint {
        targetSdk = appiumTargetSdk.toInt()
    }

    testOptions {
        targetSdk = appiumTargetSdk.toInt()
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("composeOnRelease") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        val normalized = AppiumJvmTarget.resolveNormalized(appiumJvmTarget)
        jvmTarget.set(
            JvmTarget.entries.firstOrNull { it.target == normalized }
                ?: throw GradleException(
                    "Unsupported appiumJvmTarget \"$appiumJvmTarget\" (normalized \"$normalized\"). " +
                        "Use one of: ${JvmTarget.entries.joinToString { it.target }}",
                ),
        )
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate {
                from(components["composeOnRelease"])
            }
        }
    }
}

dependencies {
    // additionalAppDependencies placeholder (don't change or delete this line)

    // RecyclerView is supplied by the AUT; packaging another copy can shadow its implementation.
    compileOnly(libs.androidx.recyclerview)
    androidTestImplementation(libs.androidx.recyclerview)

    // Older Activity classes in the test APK can shadow the AUT's initializeViewTreeOwners API.
    api(libs.androidx.activity)
    api(libs.androidx.activity.ktx)
    "composeOnApi"(libs.androidx.activity.compose)
    // Real LazyColumn and text editing fixtures; never package Foundation into the production server.
    "androidTestComposeOnImplementation"(libs.androidx.compose.foundation.test)

    api(libs.ultron.android) {
        exclude(group = "androidx.recyclerview", module = "recyclerview")
        // Use the non-transitive espresso-contrib declaration below to avoid AUT resource conflicts.
        exclude(group = "androidx.test.espresso", module = "espresso-contrib")
        // Ultron's accessibility framework also pulls Material resources into the test APK.
        exclude(group = "com.google.android.material", module = "material")
    }
    "composeOnApi"(libs.ultron.compose) {
        exclude(group = "androidx.recyclerview", module = "recyclerview")
        exclude(group = "androidx.test.espresso", module = "espresso-contrib")
        exclude(group = "com.google.android.material", module = "material")
    }

    api(libs.androidx.annotation) {
        withCapabilityVersion(project, "appiumAnnotationVersion", libs.versions.annotation.get())
    }
    api(libs.androidx.test.espresso.contrib) {
        withCapabilityVersion(project, "appiumEspressoVersion", libs.versions.espresso.get())
        // Exclude transitive dependencies to limit conflicts with AndroidX libraries from AUT.
        // Link to PR with fix and discussion https://github.com/appium/appium-espresso-driver/pull/596
        isTransitive = false
    }
    api(libs.androidx.test.espresso.web) {
        withCapabilityVersion(project, "appiumEspressoVersion", libs.versions.espresso.get())
        because("Espresso Web Atoms support (mobile: webAtoms)")
    }
    api(libs.androidx.test.uiautomator) {
        withCapabilityVersion(project, "appiumUiAutomatorVersion", libs.versions.uiautomator.get())
        because("UiAutomator support (mobile: uiautomator)")
    }
    api(libs.androidx.test.core) {
        withCapabilityVersion(project, "appiumAndroidxTestVersion", libs.versions.androidxTest.get())
    }
    api(libs.androidx.test.runner) {
        withCapabilityVersion(project, "appiumAndroidxTestVersion", libs.versions.androidxTest.get())
    }
    api(libs.androidx.test.rules) {
        withCapabilityVersion(project, "appiumAndroidxTestVersion", libs.versions.androidxTest.get())
    }
    api(libs.gson) {
        withCapabilityVersion(project, "appiumGsonVersion", libs.versions.gson.get())
    }
    api(libs.nanohttpd.webserver) {
        withCapabilityVersion(project, "appiumNanohttpdVersion", libs.versions.nanohttpd.get())
    }
    api(libs.kotlin.stdlib) {
        withCapabilityVersion(project, "appiumKotlin", libs.versions.kotlin.get())
    }
    api(libs.kotlin.reflect) {
        withCapabilityVersion(project, "appiumKotlin", libs.versions.kotlin.get())
    }
    "composeOnApi"(libs.androidx.compose.ui.test) {
        withCapabilityVersion(project, "appiumComposeVersion", libs.versions.composeUiTest.get())
        because("Android Compose support")
    }
    "composeOnApi"(libs.androidx.compose.ui.test.junit4) {
        withCapabilityVersion(project, "appiumComposeVersion", libs.versions.composeUiTest.get())
        because("Android Compose support")
    }

    testImplementation(libs.androidx.test.espresso.contrib) {
        withCapabilityVersion(project, "appiumEspressoVersion", libs.versions.espresso.get())
    }
    testImplementation(libs.junit) {
        withCapabilityVersion(project, "appiumJUnitVersion", libs.versions.junit.get())
    }
    androidTestImplementation(libs.junit) {
        withCapabilityVersion(project, "appiumJUnitVersion", libs.versions.junit.get())
    }
    testImplementation(libs.robolectric) {
        withCapabilityVersion(project, "appiumRobolectricVersion", libs.versions.robolectric.get())
    }
    testImplementation(libs.kotlin.test) {
        withCapabilityVersion(project, "appiumKotlin", libs.versions.kotlin.get())
    }
    testImplementation(libs.kotlin.test.junit) {
        withCapabilityVersion(project, "appiumKotlin", libs.versions.kotlin.get())
    }

    constraints {
        api(libs.kotlin.stdlib.jdk8) {
            version {
                require(resolveCapabilityVersion("appiumKotlin", libs.versions.kotlin.get()))
            }
        }
    }
}
