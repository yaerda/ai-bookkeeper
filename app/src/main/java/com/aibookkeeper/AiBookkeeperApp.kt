package com.aibookkeeper

import android.app.Application
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.aibookkeeper.feature.capture.screenshot.ShareImageReceiverActivity
import com.aibookkeeper.feature.sync.auth.AuthManager
import com.aibookkeeper.feature.sync.queue.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AiBookkeeperApp : Application() {

    @Inject lateinit var authManager: AuthManager
    @Inject lateinit var syncScheduler: SyncScheduler

    override fun onCreate() {
        super.onCreate()
        authManager.initialize()
        syncScheduler.start()
        publishShareShortcut()
    }

    private fun publishShareShortcut() {
        val shortcut = ShortcutInfoCompat.Builder(this, "share_screenshot_capture")
            .setShortLabel("AI 截图记账")
            .setLongLabel("截图识别并记账")
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(
                Intent(Intent.ACTION_SEND).apply {
                    setClass(this@AiBookkeeperApp, ShareImageReceiverActivity::class.java)
                    type = "image/*"
                }
            )
            .setCategories(setOf("com.aibookkeeper.category.SCREENSHOT_CAPTURE"))
            .setIsConversation()
            .build()

        ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
    }
}
