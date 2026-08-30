package io.github.hatake716.dango.data.net

import io.github.hatake716.dango.data.db.ConnectionEntity
import io.github.hatake716.dango.domain.model.FsPath
import java.io.IOException

/** 対応プロトコル（SPEC §7.1） */
enum class NetProtocol(val scheme: String, val defaultPort: Int) {
    SMB("smb", 445),
    SFTP("sftp", 22),
    WEBDAV("webdav", 443),
    FTP("ftp", 21),
    ;

    companion object {
        val schemes = entries.map { it.scheme }.toSet()
        fun of(scheme: String): NetProtocol? = entries.find { it.scheme == scheme }
        fun ofName(name: String): NetProtocol =
            entries.find { it.scheme == name } ?: SMB
    }
}

/** パスワード未保存・認証失敗（UI がパスワード入力へ誘導する） */
class NetworkAuthException(message: String? = null) : IOException(message)

/** ホスト鍵が初回記録と一致しない（TOFU 検証。SPEC §7.2 既知ホスト） */
class HostKeyChangedException(message: String? = null) : IOException(message)

/** ネットワークパス: scheme=プロトコル、先頭セグメントが接続ID */
object NetPaths {
    fun root(connection: ConnectionEntity): FsPath =
        FsPath(NetProtocol.ofName(connection.protocol).scheme, listOf(connection.id.toString()))

    fun connectionId(path: FsPath): Long =
        path.segments.firstOrNull()?.toLongOrNull() ?: -1L

    fun remoteSegments(path: FsPath): List<String> = path.segments.drop(1)

    fun isNetwork(path: FsPath): Boolean = path.scheme in NetProtocol.schemes
}
