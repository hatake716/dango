package io.github.hatake716.dango.data.fs

/** Finder 風の名前生成規則（SPEC §6.3: 重複時は連番、複製は「〜のコピー」） */
object NameUtils {

    /** 拡張子を除いた部分と拡張子（ドット付き。フォルダ・拡張子なしは空文字）に分ける */
    fun splitExtension(name: String, isDir: Boolean): Pair<String, String> {
        if (isDir) return name to ""
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name to "" // 先頭ドット（隠しファイル）は拡張子扱いしない
        return name.substring(0, dot) to name.substring(dot)
    }

    // /storage の FUSE は大文字小文字を区別しないため、衝突回避も ignoreCase で判定する
    private fun Set<String>.containsName(name: String): Boolean =
        any { it.equals(name, ignoreCase = true) }

    /** 「name 2」「name 3」... 形式で existing と衝突しない名前を返す */
    fun uniqueName(existing: Set<String>, desired: String, isDir: Boolean): String {
        if (!existing.containsName(desired)) return desired
        val (base, ext) = splitExtension(desired, isDir)
        var n = 2
        while (true) {
            val candidate = "$base $n$ext"
            if (!existing.containsName(candidate)) return candidate
            n++
        }
    }

    /** 「name のコピー」「name のコピー 2」... 形式（SPEC §6.3 複製） */
    fun duplicateName(existing: Set<String>, name: String, isDir: Boolean): String {
        val (base, ext) = splitExtension(name, isDir)
        val first = "$base のコピー$ext"
        if (!existing.containsName(first)) return first
        var n = 2
        while (true) {
            val candidate = "$base のコピー $n$ext"
            if (!existing.containsName(candidate)) return candidate
            n++
        }
    }

    /** リネーム入力の妥当性検査。問題なければ null、問題があれば理由を返す */
    fun validate(name: String): NameError? = when {
        name.isBlank() -> NameError.EMPTY
        name.contains('/') || name.contains('\u0000') -> NameError.INVALID_CHAR
        name == "." || name == ".." -> NameError.INVALID_CHAR
        name.length > 255 -> NameError.TOO_LONG
        else -> null
    }

    enum class NameError { EMPTY, INVALID_CHAR, TOO_LONG }
}
