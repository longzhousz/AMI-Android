plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("com.diffplug.spotless") version "8.11.0"
}

// Pin the formatter inputs for CI as well as editor integrations.
val kotlinStyle =
    mapOf(
        "ktlint_code_style" to "ktlint_official",
        "max_line_length" to "160",
        "ktlint_standard_no-wildcard-imports" to "disabled",
        "ktlint_standard_max-line-length" to "disabled",
        "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
    )

spotless {
    kotlin {
        target("app/src/**/*.kt")
        ktlint("1.8.0").editorConfigOverride(kotlinStyle)
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktlint("1.8.0").editorConfigOverride(kotlinStyle)
    }
}
