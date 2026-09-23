package io.github.hatake716.dango.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * アニメーション仕様の一元管理（SPEC §5「すべて MotionScheme の設定値で一括管理」）。
 * 画面側は数値を直書きせず、ここのトークンを使う。
 *
 * 端末の「アニメーションを減らす」（ANIMATOR_DURATION_SCALE）は Compose の
 * MotionDurationScale が自動で適用するため、ここで倍率を掛けない（二重適用になる）
 */
object DangoMotion {
    /** macOS の標準的なイージング（NSAnimation の既定に近い ease-in-out） */
    val MacEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** フォルダを開く / 戻る: 0.96→1.0 のズーム＋フェード */
    const val FOLDER_NAV_MS = 200
    const val FOLDER_NAV_SCALE = 0.96f
    fun <T> folderNav(): FiniteAnimationSpec<T> = tween(FOLDER_NAV_MS, easing = EaseOutCubic)

    /** Quick Look の起動/終了（起点アイテムからのズーム） */
    fun <T> quickLook(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
    const val QUICK_LOOK_FADE_MS = 280
    /** 閉じるときの暗幕は中身の縮小と同時に消える長さにする */
    const val QUICK_LOOK_FADE_OUT_MS = 200
    /** 上部バーは拡大が始まってから少し遅れて出す */
    const val QUICK_LOOK_CHROME_DELAY_MS = 90
    /** 起点アイテムが画面に無いときは、この倍率とのあいだでフェードする */
    const val QUICK_LOOK_FALLBACK_SCALE = 0.9f
    /** 画像の読み込みを待ってから拡大を始める上限（空のパネルを拡大させない） */
    const val QUICK_LOOK_HOLD_MAX_MS = 200L

    /** 選択ハイライト: 付くときは 80ms、外れるときは Finder 同様に素早く */
    fun <T> selectionIn(): FiniteAnimationSpec<T> = tween(80, easing = LinearEasing)
    fun <T> selectionOut(): FiniteAnimationSpec<T> = tween(50, easing = LinearEasing)

    /** 並べ替え・表示切替でアイテムが新しい位置へ移動 */
    fun <T> reorder(): FiniteAnimationSpec<T> = tween(250, easing = MacEase)

    /** ツールバーのセグメンテッドコントロールのつまみ移動 */
    fun <T> segment(): FiniteAnimationSpec<T> = tween(200, easing = MacEase)

    /** 表示モード切替のクロスフェード */
    fun <T> viewSwitch(): FiniteAnimationSpec<T> = tween(150, easing = MacEase)

    /** リストのフォルダ展開（▸ の回転と子の出現） */
    const val EXPAND_MS = 180
    fun <T> expand(): FiniteAnimationSpec<T> = tween(EXPAND_MS, easing = MacEase)

    /** サイドバー開閉 */
    fun <T> sidebar(): FiniteAnimationSpec<T> = tween(220, easing = MacEase)

    /** カラム追加（スライドイン＋自動スクロール） */
    const val COLUMN_ADD_MS = 200
    fun <T> columnAdd(): FiniteAnimationSpec<T> = tween(COLUMN_ADD_MS, easing = EaseOutCubic)

    /** 削除: ゴミ箱へ吸い込まれる縮小移動 */
    const val TRASH_FLIGHT_MS = 300
    fun <T> trashFlight(): FiniteAnimationSpec<T> = tween(TRASH_FLIGHT_MS, easing = EaseInCubic)

    /** コピー完了の強調 1.0→1.05→1.0（半分ずつ） */
    const val PULSE_HALF_MS = 125
    const val PULSE_SCALE = 1.05f

    /** ドロップ先のバウンス */
    fun <T> bounceUp(): FiniteAnimationSpec<T> = tween(90, easing = EaseOutCubic)
    fun <T> bounceDown(): FiniteAnimationSpec<T> = tween(140, easing = MacEase)

    /** ライト/ダーク切替のクロスフェード */
    fun <T> themeFade(): FiniteAnimationSpec<T> = tween(300, easing = LinearEasing)

    /** 画像プレビューのダブルタップ拡大 */
    fun <T> zoom(): FiniteAnimationSpec<T> = tween(250, easing = EaseOutCubic)

    /** macOS のメニュー: ほぼ即時に出て、閉じるときだけ短くフェード */
    fun <T> menuIn(): FiniteAnimationSpec<T> = tween(60, easing = LinearEasing)
    fun <T> menuOut(): FiniteAnimationSpec<T> = tween(150, easing = LinearEasing)

    /** ボタン押下のハイライト（リップルの代わり） */
    fun <T> pressOut(): FiniteAnimationSpec<T> = tween(120, easing = LinearEasing)

    /** 汎用の短いフェード（状態の出し入れ・バーの高さ変化など） */
    fun <T> fade(): FiniteAnimationSpec<T> = tween(150, easing = MacEase)
    fun <T> bar(): FiniteAnimationSpec<T> = tween(200, easing = MacEase)

    /** 読み込み中スピナーは遅れて出す（ローカルの速いフォルダでちらつかせない） */
    const val SPINNER_DELAY_MS = 300

    /** フォルダ遷移のズームを実内容で再生するため、読み込み完了を待つ上限 */
    const val NAV_HOLD_MAX_MS = 180L
}
