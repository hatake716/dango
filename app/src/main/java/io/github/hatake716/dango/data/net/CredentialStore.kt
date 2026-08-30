package io.github.hatake716.dango.data.net

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 資格情報の保管（SPEC §2: Android Keystore + EncryptedSharedPreferences） */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "net_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun password(connectionId: Long): String? = prefs.getString("pw_$connectionId", null)

    fun setPassword(connectionId: Long, password: String?) {
        prefs.edit().apply {
            if (password == null) remove("pw_$connectionId") else putString("pw_$connectionId", password)
        }.apply()
    }

    /** SSH ホスト鍵の指紋（TOFU: 初回接続時に記録し、以後一致を検証する） */
    fun hostKey(connectionId: Long): String? = prefs.getString("hostkey_$connectionId", null)

    fun setHostKey(connectionId: Long, fingerprint: String) {
        prefs.edit().putString("hostkey_$connectionId", fingerprint).apply()
    }

    fun clear(connectionId: Long) {
        prefs.edit()
            .remove("pw_$connectionId")
            .remove("hostkey_$connectionId")
            .apply()
    }
}
