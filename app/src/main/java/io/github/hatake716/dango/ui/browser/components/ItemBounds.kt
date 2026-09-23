package io.github.hatake716.dango.ui.browser.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
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

    /** 絵そのものの枠として登録された（アイテム全体ではない）キー */
    private val exactKeys = HashSet<String>()

    /** [key] の矩形が絵そのものの枠か（Quick Look はそのまま起点に使える） */
    fun isExact(key: String): Boolean = key in exactKeys

    internal fun put(key: String, rect: Rect, exact: Boolean) {
        bounds[key] = rect
        if (exact) exactKeys += key else exactKeys -= key
    }

    internal fun remove(key: String, rect: Rect?) {
        // 同じキーの新しい配置（別ビュー）で上書き済みなら消さない
        if (rect == null || bounds[key] == rect) {
            bounds.remove(key)
            exactKeys -= key
        }
    }

    /** ゴミ箱など、アニメーションの到達点として使う名前付きの位置 */
    val targets = HashMap<String, Rect>()

    /**
     * 飛行アニメーション中で元の位置には描かない項目。
     * 項目側は graphicsLayer の中で読む（描画フェーズだけが更新され再コンポーズしない）
     */
    var hiddenKeys by mutableStateOf<Set<String>>(emptySet())

    /** ゴミ箱へ項目が着地した回数（ゴミ箱アイコンを弾ませる合図） */
    val trashLanded = mutableIntStateOf(0)
}

val LocalItemBounds = staticCompositionLocalOf<ItemBoundsRegistry?> { null }

/** true の間は一覧の配置アニメーションを止める（ペイン幅が連続的に変わる間など） */
val LocalSuppressPlacement = compositionLocalOf { false }

@Composable
fun rememberItemBoundsRegistry(): ItemBoundsRegistry = remember { ItemBoundsRegistry() }

/**
 * アイテムの位置を [LocalItemBounds] に登録する（破棄時に削除）。
 * ゴミ箱へ飛んでいる間（[ItemBoundsRegistry.hiddenKeys]）は元の位置に描かない。
 * 起点として登録した要素はどのビューでも必ず隠れるよう、ここでまとめて扱う
 */
fun Modifier.registerItemBounds(key: String, exact: Boolean = false): Modifier = composed {
    val registry = LocalItemBounds.current ?: return@composed this
    val holder = remember(key) { arrayOfNulls<Rect>(1) }
    DisposableEffect(registry, key) {
        onDispose { registry.remove(key, holder[0]) }
    }
    this
        .graphicsLayer { alpha = if (key in registry.hiddenKeys) 0f else 1f }
        .onGloballyPositioned { coords ->
            if (coords.isAttached) {
                val r = coords.boundsInRoot()
                holder[0] = r
                registry.put(key, r, exact)
            }
        }
}

/** 項目のアイコン（絵）部分だけの位置を登録するときのキー */
fun iconBoundsKey(key: String): String = "icon:$key"

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
