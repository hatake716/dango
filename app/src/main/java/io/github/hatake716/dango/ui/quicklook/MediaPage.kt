package io.github.hatake716.dango.ui.quicklook

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * 動画・音声プレビュー（SPEC §6.5: Media3。再生/一時停止・シーク・倍速）。
 * PiP・字幕・バックグラウンド再生は M6 で対応（docs/PROGRESS.md）。
 */
@Composable
fun MediaPage(
    entry: io.github.hatake716.dango.domain.model.FsEntry,
    isActive: Boolean,
) {
    val context = LocalContext.current
    var speed by remember { mutableFloatStateOf(1f) }
    val player = remember {
        ExoPlayer.Builder(context)
            // 他アプリの再生を止め、こちらも奪われたら止まる（オーディオフォーカス）
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
            .apply {
                entry.fileUri?.let { setMediaItem(MediaItem.fromUri(it)) }
                prepare()
            }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    // アプリがバックグラウンドへ回ったら一時停止（バックグラウンド再生は M6 で対応）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(isActive) {
        if (isActive) player.play() else player.pause()
    }
    LaunchedEffect(speed) {
        player.setPlaybackSpeed(speed)
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    artworkDisplayMode = PlayerView.ARTWORK_DISPLAY_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // 倍速切替（SPEC §6.5）
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.5f)),
        ) {
            listOf(0.5f, 1f, 1.5f, 2f).forEach { s ->
                Text(
                    text = if (s == 1f) "1x" else "${s}x",
                    color = if (speed == s) Color.White else Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable { speed = s }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
    }
}
