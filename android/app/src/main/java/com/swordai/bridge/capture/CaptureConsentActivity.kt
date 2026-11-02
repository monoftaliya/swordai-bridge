package com.swordai.bridge.capture

import android.app.Activity
import android.content.ActivityNotFoundException
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Прозрачная активность, показывающая системный диалог согласия на захват экрана. */
class CaptureConsentActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // Активность ещё на экране: сервис может перейти в режим mediaProjection.
        ScreenCapture.onConsentResult(this, result.resultCode, result.data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Захват всего экрана: агенту нужен весь экран, а не одно приложение.
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            ScreenCapture.onConsentResult(this, Activity.RESULT_CANCELED, null)
            finish()
        }
    }
}
