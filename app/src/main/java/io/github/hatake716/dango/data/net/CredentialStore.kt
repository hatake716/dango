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

    /**
     * SSH ホスト鍵の指紋（TOFU: 初回接続時に記録し、以後一致を検証する）。
     * キーは host:port（接続 ID だと未保存接続のテストが全て id=0 を共有してしまう）
     */
    fun hostKey(hostPort: String): String? = prefs.getString("hostkey_$hostPort", null)

    fun setHostKey(hostPort: String, fingerprint: String) {
        prefs.edit().putString("hostkey_$hostPort", fingerprint).apply()
    }

    fun clearHostKey(hostPort: String) {
        prefs.edit().remove("hostkey_$hostPort").apply()
    }

    fun clear(connectionId: Long) {
        prefs.edit()
            .remove("pw_$connectionId")
            .apply()
    }
}
