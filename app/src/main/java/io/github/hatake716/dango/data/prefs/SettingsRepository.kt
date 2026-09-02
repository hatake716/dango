package io.github.hatake716.dango.data.prefs

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.hatake716.dango.domain.model.SortKey
import io.github.hatake716.dango.domain.model.SortSpec
import io.github.hatake716.dango.domain.model.ThemeMode
import io.github.hatake716.dango.domain.model.ViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val viewMode: ViewMode = ViewMode.ICON,
    val sort: SortSpec = SortSpec(),
    val showHidden: Boolean = false,
    val onboardingDone: Boolean = false,
    /** アイコン表示のサイズ（SPEC §4.4: ピンチで 48〜256dp） */
    val iconSizeDp: Int = 76,
    /** シングルタップで開く（SPEC §6.1, §10） */
    val singleTapOpen: Boolean = false,
    /** Material You 動的カラー（SPEC §9: 既定オフ。有効時はアクセントのみ追従） */
    val dynamicColor: Boolean = false,
    /** 起動時の生体認証ロック（SPEC §10 安全） */
    val biometricLock: Boolean = false,
    /** ゴミ箱の自動削除日数（SPEC §6.6） */
    val trashAutoDays: Int = 30,
    /** リスト表示の列幅（SPEC §4.4 列カスタマイズ。名前列は残り幅を使う） */
    val listDateWidthDp: Int = 128,
    val listSizeWidthDp: Int = 76,
    val listKindWidthDp: Int = 112,
)

/** テーマ・表示設定の永続化（SPEC §8.1 data/prefs。フォルダごとの記憶は M1 以降で Room へ） */
class SettingsRepository(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { p ->
            Settings(
                themeMode = enumOr(p[KEY_THEME], ThemeMode.SYSTEM),
                viewMode = enumOr(p[KEY_VIEW_MODE], ViewMode.ICON),
                sort = SortSpec(
                    key = enumOr(p[KEY_SORT_KEY], SortKey.NAME),
                    ascending = p[KEY_SORT_ASC] ?: true,
                    foldersFirst = p[KEY_FOLDERS_FIRST] ?: true,
                ),
                showHidden = p[KEY_SHOW_HIDDEN] ?: false,
                onboardingDone = p[KEY_ONBOARDING_DONE] ?: false,
                iconSizeDp = (p[KEY_ICON_SIZE] ?: 76).coerceIn(48, 256),
                singleTapOpen = p[KEY_SINGLE_TAP] ?: false,
                dynamicColor = p[KEY_DYNAMIC_COLOR] ?: false,
                biometricLock = p[KEY_BIOMETRIC] ?: false,
                trashAutoDays = p[KEY_TRASH_DAYS] ?: 30,
                listDateWidthDp = (p[KEY_LIST_DATE_W] ?: 128).coerceIn(56, 400),
                listSizeWidthDp = (p[KEY_LIST_SIZE_W] ?: 76).coerceIn(40, 400),
                listKindWidthDp = (p[KEY_LIST_KIND_W] ?: 112).coerceIn(48, 400),
            )
        }

    suspend fun setIconSizeDp(size: Int) {
        context.dataStore.edit { it[KEY_ICON_SIZE] = size.coerceIn(48, 256) }
    }

    suspend fun setListColumnWidths(dateDp: Int, sizeDp: Int, kindDp: Int) {
        context.dataStore.edit {
            it[KEY_LIST_DATE_W] = dateDp.coerceIn(56, 400)
            it[KEY_LIST_SIZE_W] = sizeDp.coerceIn(40, 400)
            it[KEY_LIST_KIND_W] = kindDp.coerceIn(48, 400)
        }
    }

    suspend fun setSingleTapOpen(value: Boolean) {
        context.dataStore.edit { it[KEY_SINGLE_TAP] = value }
    }

    suspend fun setDynamicColor(value: Boolean) {
        context.dataStore.edit { it[KEY_DYNAMIC_COLOR] = value }
    }

    suspend fun setBiometricLock(value: Boolean) {
        context.dataStore.edit { it[KEY_BIOMETRIC] = value }
    }

    suspend fun setTrashAutoDays(days: Int) {
        context.dataStore.edit { it[KEY_TRASH_DAYS] = days }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun setViewMode(mode: ViewMode) {
        context.dataStore.edit { it[KEY_VIEW_MODE] = mode.name }
    }

    // トグル類は edit 内で現在の永続値から次値を計算する
    // （ViewModel 側の state はディスク往復後にしか更新されず、連続タップで stale 読みになるため）

    suspend fun toggleSortKey(key: SortKey) {
        context.dataStore.edit { p ->
            val current = enumOr(p[KEY_SORT_KEY], SortKey.NAME)
            if (current == key) {
                p[KEY_SORT_ASC] = !(p[KEY_SORT_ASC] ?: true)
            } else {
                p[KEY_SORT_KEY] = key.name
                p[KEY_SORT_ASC] = true
            }
        }
    }

    suspend fun toggleFoldersFirst() {
        context.dataStore.edit { it[KEY_FOLDERS_FIRST] = !(it[KEY_FOLDERS_FIRST] ?: true) }
    }

    suspend fun toggleShowHidden() {
        context.dataStore.edit { it[KEY_SHOW_HIDDEN] = !(it[KEY_SHOW_HIDDEN] ?: false) }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        context.dataStore.edit { it[KEY_ONBOARDING_DONE] = done }
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_VIEW_MODE = stringPreferencesKey("view_mode")
        val KEY_SORT_KEY = stringPreferencesKey("sort_key")
        val KEY_SORT_ASC = booleanPreferencesKey("sort_ascending")
        val KEY_FOLDERS_FIRST = booleanPreferencesKey("folders_first")
        val KEY_SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val KEY_ICON_SIZE = intPreferencesKey("icon_size_dp")
        val KEY_LIST_DATE_W = intPreferencesKey("list_date_width_dp")
        val KEY_LIST_SIZE_W = intPreferencesKey("list_size_width_dp")
        val KEY_LIST_KIND_W = intPreferencesKey("list_kind_width_dp")
        val KEY_SINGLE_TAP = booleanPreferencesKey("single_tap_open")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_BIOMETRIC = booleanPreferencesKey("biometric_lock")
        val KEY_TRASH_DAYS = intPreferencesKey("trash_auto_days")
    }
}
