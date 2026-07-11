package dev.encgallery.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

object LocaleHelper {

    fun wrap(base: Context, language: String): Context {
        if (language.isBlank() || language == AppSettings.LANGUAGE_SYSTEM) return base
        val locale = Locale.forLanguageTag(language)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    fun wrapForLanguage(base: Context, language: String): Context {
        val locale = if (language.isBlank() || language == AppSettings.LANGUAGE_SYSTEM) {
            Resources.getSystem().configuration.locales[0]
        } else {
            Locale.forLanguageTag(language)
        }
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        val localized = base.createConfigurationContext(config)
        return object : ContextWrapper(base) {
            override fun getResources(): Resources = localized.resources
            override fun getAssets(): AssetManager = localized.assets
        }
    }
}
