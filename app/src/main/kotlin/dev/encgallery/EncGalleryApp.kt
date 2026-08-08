package dev.encgallery

import android.app.Application
import dev.encgallery.crypto.PasswordChange
import dev.encgallery.crypto.VaultDataKey
import dev.encgallery.gallery.ThumbnailLoader
import dev.encgallery.logging.EncLog
import dev.encgallery.settings.AppSettings

class EncGalleryApp : Application() {

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ThumbnailLoader.onTrimMemory(level)
    }

    override fun onCreate() {
        super.onCreate()

        EncLog.init(filesDir = filesDir, enabled = false)

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            EncLog.crash(thread, throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }

        AppSettings.init(this)

        ThumbnailLoader.configure(this)

        VaultDataKey.init(this)

        PasswordChange.recoverIfPending(filesDir)?.let { EncLog.w("App", it) }

        EncLog.i("App", "EncGalleryApp.onCreate — process started")
        EncLog.d("App", "filesDir=$filesDir")
        EncLog.d("App", "loggingEnabled=${AppSettings.loggingEnabled.value}")
        EncLog.d("App", "screenshotsAllowed=${AppSettings.screenshotsAllowed.value}")
    }
}
