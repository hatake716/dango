package io.github.hatake716.dango.ui.browser.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * 表示中アイテムの画面上の位置（ルート座標・px）を記録する。
 * Quick Look を押したアイテムから拡大させる（SPEC §5）・削除をゴミ箱へ吸い込ませる
 * 等、アイテムの実位置を起点にするアニメーションで使う。描画には使わないため
 * 通常の Map で持ち、再コンポーズを起こさない
 */
class ItemBoundsRegistry {
    private val bounds = HashMap<String, Rect>()

    /** 可視アイテムの位置。画面外（未配置）なら null */
    operator fun get(key: String): Rect? = bounds[key]

    internal fun put(key: String, rect: Rect) {
        bounds[key] = rect
    }

    internal fun remove(key: String, rect: Rect?) {
        // 同じキーの新しい配置（別ビュー）で上書き済みなら消さない
        if (rect == null || bounds[key] == rect) bounds.remove(key)
    }

    /** ゴミ箱など、アニメーションの到達点として使う名前付きの位置 */
    val targets = HashMap<String, Rect>()
}

val LocalItemBounds = staticCompositionLocalOf<ItemBoundsRegistry?> { null }

@Composable
fun rememberItemBoundsRegistry(): ItemBoundsRegistry = remember { ItemBoundsRegistry() }

/** アイテムの位置を [LocalItemBounds] に登録する（破棄時に削除） */
fun Modifier.registerItemBounds(key: String): Modifier = composed {
    val registry = LocalItemBounds.current ?: return@composed this
    val holder = remember(key) { arrayOfNulls<Rect>(1) }
    DisposableEffect(registry, key) {
        onDispose { registry.remove(key, holder[0]) }
    }
    onGloballyPositioned { coords ->
        if (coords.isAttached) {
            val r = coords.boundsInRoot()
            holder[0] = r
            registry.put(key, r)
        }
    }
}

/** アニメーション到達点（ゴミ箱の行・削除ボタンなど）の位置を登録する */
fun Modifier.registerAnimationTarget(name: String): Modifier = composed {
    val registry = LocalItemBounds.current ?: return@composed this
    DisposableEffect(registry, name) {
        onDispose { registry.targets.remove(name) }
    }
    onGloballyPositioned { coords ->
        if (coords.isAttached) registry.targets[name] = coords.boundsInRoot()
    }
}
