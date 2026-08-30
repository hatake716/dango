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
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
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

/** 生体認証ロック画面（SPEC §10。認証は端末の BiometricPrompt に委ねる） */
@androidx.compose.runtime.Composable
private fun LockScreen(onUnlock: () -> Unit) {
    val colors = DangoTheme.colors
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.windowBackground),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        androidx.compose.material3.Text(text = "🍡", fontSize = androidx.compose.ui.unit.TextUnit(48f, androidx.compose.ui.unit.TextUnitType.Sp))
        androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
        androidx.compose.material3.Text(
            text = androidx.compose.ui.res.stringResource(R.string.lock_title),
            color = colors.textPrimary,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        androidx.compose.material3.Button(onClick = onUnlock) {
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.lock_unlock))
        }
    }
}

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

            // 起動時の生体認証ロック（SPEC §10 安全）
            var unlocked by rememberSaveable { mutableStateOf(false) }
            val needsLock = settings?.biometricLock == true && !unlocked
            LaunchedEffect(needsLock) {
                if (needsLock) showBiometricPrompt { unlocked = true }
            }

            DangoTheme(
                themeMode = themeMode,
                dynamicColor = settings?.dynamicColor == true,
            ) {
                when {
                    settings == null -> {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(DangoTheme.colors.windowBackground),
                        )
                    }
                    needsLock -> {
                        LockScreen(onUnlock = { showBiometricPrompt { unlocked = true } })
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

    /** 端末の BiometricPrompt（生体認証＋画面ロック資格情報）。SPEC §10 */
    private fun showBiometricPrompt(onSuccess: () -> Unit) {
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.lock_prompt_title))
            .setSubtitle(getString(R.string.lock_prompt_subtitle))
            .setAllowedAuthenticators(
                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        prompt.authenticate(
            android.os.CancellationSignal(),
            mainExecutor,
            object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?,
                ) {
                    onSuccess()
                }
            },
        )
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
