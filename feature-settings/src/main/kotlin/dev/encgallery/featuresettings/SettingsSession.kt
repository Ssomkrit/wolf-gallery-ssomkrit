package dev.encgallery.featuresettings

object SettingsSession {

    var lastOpenCategory: SettingsCategory = SettingsCategory.HOME

    var lastSecretChangeOpen: Boolean = false

    val scrollByKey: MutableMap<String, Int> = mutableMapOf()

    fun reset() {
        lastOpenCategory = SettingsCategory.HOME
        lastSecretChangeOpen = false
        scrollByKey.clear()
    }
}

enum class SettingsCategory { HOME, SECURITY, APPEARANCE, LANGUAGE, APP_ICON, TRASH, TRANSFER, SUPPORT, TESTS_AND_LOGS, ABOUT }
