import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "dev.agentry"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.1")
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        name = "Agentry"
        version = "0.1.0"
        ideaVersion {
            sinceBuild = "251"
            untilBuild = "261.*"
        }
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    pluginVerification {
        // Pin explicitly to the matrix CI actually runs against, rather than `recommended()`,
        // so a new JetBrains release can't break the build without an intentional bump.
        //
        // `useInstaller = false` resolves the IDE from the IntelliJ Maven repository instead
        // of the binary installer mirror. JetBrains stopped publishing GA tarballs at
        // download.jetbrains.com for 2025.3+ (the default installer URL returns 404 for those
        // versions), but the Maven artifacts are still available. Pinning the whole matrix to
        // the Maven path keeps the resolver consistent across versions.
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1", useInstaller = false)
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2", useInstaller = false)
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.3", useInstaller = false)
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2026.1", useInstaller = false)
            ide(IntelliJPlatformType.PyCharmCommunity, "2026.1", useInstaller = false)
            ide(IntelliJPlatformType.WebStorm, "2026.1", useInstaller = false)
            ide(IntelliJPlatformType.Rider, "2026.1", useInstaller = false)
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    wrapper {
        gradleVersion = "8.10.2"
    }
}
