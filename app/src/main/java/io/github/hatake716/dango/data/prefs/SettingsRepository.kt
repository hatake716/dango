package io.github.hatake716.dango.data.prefs

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
            )
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
    }
}
