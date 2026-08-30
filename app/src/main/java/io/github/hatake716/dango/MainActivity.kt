package io.github.hatake716.dango

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hatake716.dango.domain.model.ThemeMode
import io.github.hatake716.dango.ui.browser.BrowserScreen
import io.github.hatake716.dango.ui.browser.BrowserViewModel
import io.github.hatake716.dango.ui.browser.OnboardingScreen
import io.github.hatake716.dango.ui.theme.DangoTheme
import io.github.hatake716.dango.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: BrowserViewModel = viewModel()
            // DataStore の実値が届くまでは null。既定値で誤った画面（オンボーディング等）を
            // 一瞬描画しないよう、null の間は背景のみ表示する
            val settings = viewModel.settings.collectAsState().value
            var hasFullAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }

            // 設定アプリから戻ってきたときに権限状態を再評価する
            LifecycleResumeEffect(Unit) {
                val granted = Environment.isExternalStorageManager()
                if (granted != hasFullAccess) {
                    hasFullAccess = granted
                    viewModel.reload()
                }
                onPauseOrDispose { }
            }

            val themeMode = settings?.themeMode ?: ThemeMode.SYSTEM

            // アプリ内テーマがシステムと異なる場合もステータスバーのアイコン色を追従させる
            val dark = isDarkTheme(themeMode)
            val view = LocalView.current
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }

            // 通常モード（フォールバック）: READ_MEDIA_* を実行時要求する（SPEC §3, §11）
            val mediaPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { viewModel.reload() }
            var mediaPermissionAsked by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(hasFullAccess, settings?.onboardingDone) {
                if (settings?.onboardingDone == true && !hasFullAccess && !mediaPermissionAsked) {
                    mediaPermissionAsked = true
                    mediaPermissionLauncher.launch(mediaPermissions())
                }
            }

            DangoTheme(themeMode = themeMode) {
                when {
                    settings == null -> {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(DangoTheme.colors.windowBackground),
                        )
                    }
                    !hasFullAccess && !settings.onboardingDone -> {
                        OnboardingScreen(
                            onGrant = {
                                viewModel.completeOnboarding()
                                openFullAccessSettings()
                            },
                            onSkip = { viewModel.completeOnboarding() },
                        )
                    }
                    else -> {
                        BrowserScreen(
                            viewModel = viewModel,
                            hasFullAccess = hasFullAccess,
                            onRequestFullAccess = ::openFullAccessSettings,
                        )
                    }
                }
            }
        }
    }

    private fun mediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun openFullAccessSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}
