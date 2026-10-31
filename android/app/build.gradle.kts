import java.util.Properties

plugins {
    id("com.android.application")
}

// Ключ подписи хранится вне репозитория: без него собирается неподписанный APK.
val keyProps = Properties().apply {
    val f = file(providers.gradleProperty("zvKeys").getOrElse("../../../.keys/keystore.properties"))
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.zvonilka.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.zvonilka.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.6"
    }

    signingConfigs {
        if (!keyProps.isEmpty) create("release") {
            storeFile = file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!keyProps.isEmpty) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

// Копия звонилки внутри приложения — на случай, если сайт не откроется. Берётся из корня репозитория.
abstract class CopyWeb : DefaultTask() {
    @get:InputFiles abstract val files: ConfigurableFileCollection
    @get:OutputDirectory abstract val outDir: DirectoryProperty

    @TaskAction fun copy() {
        val web = outDir.get().asFile.resolve("web")
        web.deleteRecursively()
        web.mkdirs()
        files.forEach { it.copyTo(web.resolve(it.name), overwrite = true) }
    }
}

val copyWeb = tasks.register<CopyWeb>("copyWeb") {
    val root = rootProject.file("..")
    files.from(listOf("index.html", "sw.js", "manifest.webmanifest", "icon-192.png", "icon-512.png").map { root.resolve(it) })
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyWeb, CopyWeb::outDir)
    }
}
