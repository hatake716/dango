package io.github.hatake716.dango.data.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import io.github.hatake716.dango.data.db.ConnectionDao
import io.github.hatake716.dango.data.db.ConnectionEntity
import io.github.hatake716.dango.data.fs.Capability
import io.github.hatake716.dango.data.fs.FileSystemProvider
import io.github.hatake716.dango.data.fs.ProgressSink
import io.github.hatake716.dango.data.fs.local.LocalFileSystemProvider
import io.github.hatake716.dango.domain.model.EntryKind
import io.github.hatake716.dango.domain.model.FsEntry
import io.github.hatake716.dango.domain.model.FsPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import okio.Sink
import okio.Source
import okio.sink
import okio.source
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey
import java.util.EnumSet

/**
 * ネットワークドライブ（SPEC §7）。
 * 各プロトコルを NetBackend に抽象化し、NetworkProvider が FileSystemProvider として公開する。
 * 接続ごとにセッションをキャッシュし、操作は接続単位で直列化する。
 */

data class RemoteEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
)

interface NetBackend {
    fun list(segments: List<String>): List<RemoteEntry>
    fun stat(segments: List<String>): RemoteEntry
    fun mkdir(segments: List<String>)
    fun rename(from: List<String>, to: List<String>)
    fun delete(segments: List<String>, isDir: Boolean)
    fun openRead(segments: List<String>): InputStream
    fun openWrite(segments: List<String>): OutputStream
    fun close()
}

class NetworkDeps(
    val connectionDao: ConnectionDao,
    val credentialStore: CredentialStore,
    /** 保存していないパスワードのセッション内キャッシュ（VM が入力時に埋める） */
    val sessionPasswords: MutableMap<Long, String>,
)

class NetworkProvider(
    private val protocol: NetProtocol,
    private val deps: NetworkDeps,
) : FileSystemProvider {

    override val scheme: String = protocol.scheme

    private val sessions = mutableMapOf<Long, NetBackend>()
    private val locks = mutableMapOf<Long, Mutex>()

    private fun lockFor(id: Long): Mutex = synchronized(locks) { locks.getOrPut(id) { Mutex() } }

    private suspend fun <T> withBackend(path: FsPath, block: (NetBackend) -> T): T {
        val connId = NetPaths.connectionId(path)
        return lockFor(connId).withLock {
            withContext(Dispatchers.IO) {
                val backend = synchronized(sessions) { sessions[connId] } ?: run {
                    val conn = deps.connectionDao.byId(connId)
                        ?: throw IOException("接続が見つかりません")
                    // 直近の入力（セッション）を保存値より優先する。
                    // 古い保存パスワードが残ったまま正しい値を入れ直しても効かない事故を防ぐ
                    val password = deps.sessionPasswords[connId]
                        ?: deps.credentialStore.password(connId)
                        ?: throw NetworkAuthException()
                    val created = try {
                        createBackend(conn, password)
                    } catch (e: com.hierynomus.smbj.common.SMBRuntimeException) {
                        throw mapSmbException(e)
                    }
                    synchronized(sessions) { sessions[connId] = created }
                    created
                }
                try {
                    block(backend)
                } catch (e: NetworkAuthException) {
                    invalidate(connId)
                    throw e
                } catch (e: HostKeyChangedException) {
                    invalidate(connId)
                    throw e
                } catch (e: com.hierynomus.smbj.common.SMBRuntimeException) {
                    // smbj は RuntimeException 系。セッション切れも認証失敗もここに来る
                    invalidate(connId)
                    throw mapSmbException(e)
                } catch (e: IOException) {
                    // セッション切れの可能性があるので破棄し、次回の操作で再接続する（SPEC §7.3）
                    invalidate(connId)
                    throw e
                }
            }
        }
    }

    private fun mapSmbException(e: com.hierynomus.smbj.common.SMBRuntimeException): IOException {
        val status = (e as? com.hierynomus.mssmb2.SMBApiException)?.status?.name
        return if (status in SMB_AUTH_STATUSES) {
            NetworkAuthException(e.message)
        } else {
            IOException(e.message, e)
        }
    }

    fun invalidate(connId: Long) {
        val backend = synchronized(sessions) { sessions.remove(connId) } ?: return
        // 進行中のストリーム（プレビューDL等）を道連れにしないよう、少し置いてから閉じる
        CoroutineScope(Dispatchers.IO).launch {
            delay(60_000)
            runCatching { backend.close() }
        }
    }

    fun invalidateAll() {
        val all = synchronized(sessions) { sessions.values.toList().also { sessions.clear() } }
        all.forEach { runCatching { it.close() } }
    }

    private companion object {
        val SMB_AUTH_STATUSES = setOf(
            "STATUS_LOGON_FAILURE",
            "STATUS_ACCESS_DENIED",
            "STATUS_ACCOUNT_RESTRICTION",
            "STATUS_PASSWORD_EXPIRED",
            "STATUS_ACCOUNT_DISABLED",
            "STATUS_ACCOUNT_LOCKED_OUT",
        )
    }

    private fun createBackend(conn: ConnectionEntity, password: String): NetBackend =
        createNetBackend(protocol, conn, password, deps.credentialStore)

    private fun RemoteEntry.toFsEntry(parent: FsPath): FsEntry {
        val ext = name.substringAfterLast('.', "").lowercase()
        return FsEntry(
            path = parent.child(name),
            name = name,
            isDir = isDir,
            size = if (isDir) -1 else size,
            lastModified = mtime,
            isHidden = name.startsWith("."),
            kind = if (isDir) EntryKind.FOLDER else LocalFileSystemProvider.kindOfExtension(ext),
            previewUri = null,
            fileUri = null,
        )
    }

    override fun list(path: FsPath): Flow<FsEntry> = flow {
        val entries = withBackend(path) { it.list(NetPaths.remoteSegments(path)) }
        for (e in entries) emit(e.toFsEntry(path))
    }.flowOn(Dispatchers.IO)

    override suspend fun stat(path: FsPath): FsEntry {
        val e = withBackend(path) { it.stat(NetPaths.remoteSegments(path)) }
        val parent = path.parent ?: path
        return e.copy(name = path.name).toFsEntry(parent)
    }

    override suspend fun mkdir(path: FsPath) {
        withBackend(path) { it.mkdir(NetPaths.remoteSegments(path)) }
    }

    override suspend fun rename(from: FsPath, to: FsPath) {
        withBackend(from) { it.rename(NetPaths.remoteSegments(from), NetPaths.remoteSegments(to)) }
    }

    override suspend fun delete(path: FsPath, recursive: Boolean) {
        val entry = runCatching { stat(path) }.getOrNull()
        val isDir = entry?.isDir ?: false
        if (isDir && recursive) {
            val children = list(path).let { f ->
                val out = mutableListOf<FsEntry>()
                f.collect { out += it }
                out
            }
            for (child in children) delete(child.path, recursive = true)
        }
        withBackend(path) { it.delete(NetPaths.remoteSegments(path), isDir) }
    }

    override suspend fun openRead(path: FsPath): Source =
        withBackend(path) { it.openRead(NetPaths.remoteSegments(path)).source() }

    override suspend fun openWrite(path: FsPath, append: Boolean): Sink =
        withBackend(path) { it.openWrite(NetPaths.remoteSegments(path)).sink() }

    override suspend fun copy(from: FsPath, to: FsPath, progress: ProgressSink): Boolean = false

    override fun capabilities(): Set<Capability> = setOf(Capability.RENAME)

    override suspend fun freeSpace(path: FsPath): Long? = null
}

fun createNetBackend(
    protocol: NetProtocol,
    conn: ConnectionEntity,
    password: String,
    store: CredentialStore,
): NetBackend = when (protocol) {
    NetProtocol.SMB -> SmbBackend(conn, password)
    NetProtocol.SFTP -> SftpBackend(conn, password, store)
    NetProtocol.WEBDAV -> WebDavBackend(conn, password)
    NetProtocol.FTP -> FtpBackend(conn, password)
}

/** 接続テスト（SPEC §7.2: 接続テストボタン）。ルート一覧の取得までを検証する */
object NetworkTester {
    suspend fun test(conn: ConnectionEntity, password: String, store: CredentialStore): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val backend = createNetBackend(NetProtocol.ofName(conn.protocol), conn, password, store)
                try {
                    backend.list(emptyList()).size
                } finally {
                    backend.close()
                }
            }
        }
}

// --- SMB2/3（smbj。SPEC §7.1: SMB1 非対応） ---

private class SmbBackend(conn: ConnectionEntity, password: String) : NetBackend {
    private val client = SMBClient()
    private val connection: com.hierynomus.smbj.connection.Connection
    private val session: com.hierynomus.smbj.session.Session
    private val share: DiskShare

    init {
        // 途中で失敗したら確保済みの資源を閉じてから投げる（リーク防止）
        var conn2: com.hierynomus.smbj.connection.Connection? = null
        var sess: com.hierynomus.smbj.session.Session? = null
        try {
            conn2 = client.connect(conn.host, conn.port)
            sess = conn2.authenticate(
                AuthenticationContext(conn.username, password.toCharArray(), null),
            )
            share = sess.connectShare(conn.sharePath.trim('/')) as? DiskShare
                ?: throw IOException("共有 ${conn.sharePath} に接続できません")
            connection = conn2
            session = sess
        } catch (e: Throwable) {
            runCatching { sess?.close() }
            runCatching { conn2?.close() }
            runCatching { client.close() }
            throw e
        }
    }

    private fun p(segments: List<String>): String = segments.joinToString("\\")

    override fun list(segments: List<String>): List<RemoteEntry> =
        share.list(p(segments))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                val isDir = it.fileAttributes and
                    FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                RemoteEntry(
                    name = it.fileName,
                    isDir = isDir,
                    size = it.endOfFile,
                    mtime = it.lastWriteTime.toEpochMillis(), // 内容の変更日時（Finder の「変更日」相当）
                )
            }

    override fun stat(segments: List<String>): RemoteEntry {
        val info = share.getFileInformation(p(segments))
        val isDir = info.basicInformation.fileAttributes and
            FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
        return RemoteEntry(
            name = segments.lastOrNull() ?: "",
            isDir = isDir,
            size = info.standardInformation.endOfFile,
            mtime = info.basicInformation.lastWriteTime.toEpochMillis(),
        )
    }

    override fun mkdir(segments: List<String>) = share.mkdir(p(segments))

    override fun rename(from: List<String>, to: List<String>) {
        val isDir = stat(from).isDir
        val entry = if (isDir) {
            share.openDirectory(
                p(from),
                EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        } else {
            share.openFile(
                p(from),
                EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        }
        entry.use { it.rename(p(to), false) }
    }

    override fun delete(segments: List<String>, isDir: Boolean) {
        if (isDir) share.rmdir(p(segments), true) else share.rm(p(segments))
    }

    override fun openRead(segments: List<String>): InputStream {
        val file = share.openFile(
            p(segments),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        val stream = file.inputStream
        return object : InputStream() {
            override fun read(): Int = stream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
            override fun close() {
                stream.close()
                file.close()
            }
        }
    }

    override fun openWrite(segments: List<String>): OutputStream {
        val file = share.openFile(
            p(segments),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null,
        )
        val stream = file.outputStream
        return object : OutputStream() {
            override fun write(b: Int) = stream.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = stream.write(b, off, len)
            override fun close() {
                stream.close()
                file.close()
            }
        }
    }

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}

// --- SFTP（sshj。SPEC §7.1。ホスト鍵は TOFU で検証） ---

private class SftpBackend(
    conn: ConnectionEntity,
    password: String,
    store: CredentialStore,
) : NetBackend {
    private val ssh = SSHClient()
    private val sftp: SFTPClient
    private val basePath = conn.sharePath.trim('/').split('/').filter { it.isNotEmpty() }

    init {
        var hostKeyMismatch = false
        val hostPortKey = "${conn.host}:${conn.port}"
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean {
                val fingerprint = SecurityUtils.getFingerprint(key)
                val saved = store.hostKey(hostPortKey)
                return when {
                    saved == null -> {
                        store.setHostKey(hostPortKey, fingerprint) // 初回接続で記録（TOFU）
                        true
                    }
                    saved == fingerprint -> true
                    else -> {
                        hostKeyMismatch = true
                        false
                    }
                }
            }

            override fun findExistingAlgorithms(hostname: String?, port: Int): MutableList<String> =
                mutableListOf()
        })
        try {
            ssh.connectTimeout = 10_000
            ssh.timeout = 30_000 // 無通信切断で永久ハングしないように（読み取りタイムアウト）
            ssh.connect(conn.host, conn.port)
            ssh.authPassword(conn.username, password)
        } catch (e: net.schmizz.sshj.userauth.UserAuthException) {
            runCatching { ssh.disconnect() }
            throw NetworkAuthException(e.message)
        } catch (e: Exception) {
            runCatching { ssh.disconnect() }
            if (hostKeyMismatch) throw HostKeyChangedException()
            throw IOException(e.message, e)
        }
        sftp = ssh.newSFTPClient()
    }

    private fun p(segments: List<String>): String =
        "/" + (basePath + segments).joinToString("/")

    override fun list(segments: List<String>): List<RemoteEntry> =
        sftp.ls(p(segments)).map {
            RemoteEntry(
                name = it.name,
                isDir = it.isDirectory,
                size = it.attributes.size,
                mtime = it.attributes.mtime * 1000L,
            )
        }

    override fun stat(segments: List<String>): RemoteEntry {
        val attrs = sftp.stat(p(segments))
        return RemoteEntry(
            name = segments.lastOrNull() ?: "",
            isDir = attrs.type == FileMode.Type.DIRECTORY,
            size = attrs.size,
            mtime = attrs.mtime * 1000L,
        )
    }

    override fun mkdir(segments: List<String>) = sftp.mkdir(p(segments))

    override fun rename(from: List<String>, to: List<String>) = sftp.rename(p(from), p(to))

    override fun delete(segments: List<String>, isDir: Boolean) {
        if (isDir) sftp.rmdir(p(segments)) else sftp.rm(p(segments))
    }

    override fun openRead(segments: List<String>): InputStream {
        val file = sftp.open(p(segments))
        val stream = file.ReadAheadRemoteFileInputStream(16)
        return object : InputStream() {
            override fun read(): Int = stream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
            override fun close() {
                stream.close()
                file.close()
            }
        }
    }

    override fun openWrite(segments: List<String>): OutputStream {
        val file = sftp.open(
            p(segments),
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC),
        )
        val stream = file.RemoteFileOutputStream()
        return object : OutputStream() {
            override fun write(b: Int) = stream.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = stream.write(b, off, len)
            override fun close() {
                stream.close()
                file.close()
            }
        }
    }

    override fun close() {
        runCatching { sftp.close() }
        runCatching { ssh.disconnect() }
    }
}

// --- WebDAV（sardine-android。SPEC §7.1） ---

private class WebDavBackend(conn: ConnectionEntity, password: String) : NetBackend {
    private val sardine = OkHttpSardine().apply {
        setCredentials(conn.username, password)
    }
    private val baseUrl: String = buildString {
        val host = conn.host.trimEnd('/')
        if (host.startsWith("http://") || host.startsWith("https://")) {
            append(host)
        } else {
            append("https://")
            append(host)
            if (conn.port != 443) append(":${conn.port}")
        }
        val base = conn.sharePath.trim('/')
        if (base.isNotEmpty()) {
            append('/')
            append(base.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") })
        }
    }

    private fun url(segments: List<String>): String = buildString {
        append(baseUrl)
        for (s in segments) {
            append('/')
            append(java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"))
        }
    }

    private fun DavResource.toRemote(): RemoteEntry = RemoteEntry(
        name = name,
        isDir = isDirectory,
        size = contentLength ?: -1,
        mtime = modified?.time ?: 0L,
    )

    override fun list(segments: List<String>): List<RemoteEntry> {
        val target = url(segments)
        val targetPath = java.net.URI(target).path.trimEnd('/')
        return wrapAuth {
            sardine.list(target)
                .filter { it.href.path.trimEnd('/') != targetPath } // 自分自身の行を除く
                .map { it.toRemote() }
        }
    }

    override fun stat(segments: List<String>): RemoteEntry = wrapAuth {
        sardine.list(url(segments), 0).first().toRemote().copy(name = segments.lastOrNull() ?: "")
    }

    override fun mkdir(segments: List<String>) {
        wrapAuth { sardine.createDirectory(url(segments)) }
    }

    override fun rename(from: List<String>, to: List<String>) {
        wrapAuth { sardine.move(url(from), url(to)) }
    }

    override fun delete(segments: List<String>, isDir: Boolean) {
        wrapAuth { sardine.delete(url(segments)) }
    }

    override fun openRead(segments: List<String>): InputStream =
        wrapAuth { sardine.get(url(segments)) }

    override fun openWrite(segments: List<String>): OutputStream {
        // sardine の put はストリーミング長不定に対応しないため、一時ファイル経由で close 時に送る
        val tmp = File.createTempFile("dango-webdav", null)
        val target = url(segments)
        val fileOut = tmp.outputStream()
        return object : OutputStream() {
            override fun write(b: Int) = fileOut.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = fileOut.write(b, off, len)
            override fun close() {
                fileOut.close()
                try {
                    wrapAuth { sardine.put(target, tmp, "application/octet-stream") }
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    private fun <T> wrapAuth(block: () -> T): T = try {
        block()
    } catch (e: com.thegrizzlylabs.sardineandroid.impl.SardineException) {
        if (e.statusCode == 401 || e.statusCode == 403) throw NetworkAuthException(e.message)
        throw e
    }

    override fun close() = Unit
}

// --- FTP（commons-net。SPEC §7.1: 平文 FTP は警告表示） ---

private class FtpBackend(
    private val conn: ConnectionEntity,
    private val password: String,
) : NetBackend {

    // 制御用クライアント（一覧・stat・mkdir 等のメタデータ操作専用）
    private val client = newClient()
    private val basePath = conn.sharePath.trim('/').split('/').filter { it.isNotEmpty() }

    /**
     * commons-net は retrieveFileStream/storeFileStream の完了（completePendingCommand）まで
     * 同一クライアントに他のコマンドを発行できない。転送中の一覧取得や同一接続内コピーが
     * 制御コネクションを壊さないよう、ストリーム転送は毎回専用クライアントを張る。
     */
    private fun newClient(): FTPClient = FTPClient().apply {
        controlEncoding = "UTF-8"
        connectTimeout = 10_000
        connect(conn.host, conn.port)
        soTimeout = 30_000 // 無通信切断で永久ハングしないように
        if (!login(conn.username, password)) {
            runCatching { disconnect() }
            throw NetworkAuthException("ログインできません")
        }
        enterLocalPassiveMode()
        setFileType(FTP.BINARY_FILE_TYPE)
    }

    private fun p(segments: List<String>): String =
        "/" + (basePath + segments).joinToString("/")

    override fun list(segments: List<String>): List<RemoteEntry> =
        client.listFiles(p(segments))
            .filter { it.name != "." && it.name != ".." }
            .map {
                RemoteEntry(
                    name = it.name,
                    isDir = it.isDirectory,
                    size = it.size,
                    mtime = it.timestamp?.timeInMillis ?: 0L,
                )
            }

    override fun stat(segments: List<String>): RemoteEntry {
        val parent = segments.dropLast(1)
        val name = segments.lastOrNull() ?: ""
        return list(parent).find { it.name == name }
            ?: throw IOException("not found: ${p(segments)}")
    }

    override fun mkdir(segments: List<String>) {
        if (!client.makeDirectory(p(segments))) throw IOException(client.replyString)
    }

    override fun rename(from: List<String>, to: List<String>) {
        if (!client.rename(p(from), p(to))) throw IOException(client.replyString)
    }

    override fun delete(segments: List<String>, isDir: Boolean) {
        val ok = if (isDir) client.removeDirectory(p(segments)) else client.deleteFile(p(segments))
        if (!ok) throw IOException(client.replyString)
    }

    override fun openRead(segments: List<String>): InputStream {
        val dataClient = newClient()
        val stream = dataClient.retrieveFileStream(p(segments)) ?: run {
            val reply = dataClient.replyString
            runCatching { dataClient.disconnect() }
            throw IOException(reply)
        }
        return object : InputStream() {
            override fun read(): Int = stream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
            override fun close() {
                try {
                    stream.close()
                    // 転送の成否は completePendingCommand が確定させる（途中切断を EOF と区別）
                    if (!dataClient.completePendingCommand()) {
                        throw IOException(dataClient.replyString)
                    }
                } finally {
                    runCatching { dataClient.logout() }
                    runCatching { dataClient.disconnect() }
                }
            }
        }
    }

    override fun openWrite(segments: List<String>): OutputStream {
        val dataClient = newClient()
        val stream = dataClient.storeFileStream(p(segments)) ?: run {
            val reply = dataClient.replyString
            runCatching { dataClient.disconnect() }
            throw IOException(reply)
        }
        return object : OutputStream() {
            override fun write(b: Int) = stream.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = stream.write(b, off, len)
            override fun close() {
                try {
                    stream.close()
                    // 容量不足・データ接続切断（426/451/552）をここで検出しないと
                    // 移動時に「成功扱い→ソース削除」でデータを失う
                    if (!dataClient.completePendingCommand()) {
                        throw IOException(dataClient.replyString)
                    }
                } finally {
                    runCatching { dataClient.logout() }
                    runCatching { dataClient.disconnect() }
                }
            }
        }
    }

    override fun close() {
        runCatching { client.logout() }
        runCatching { client.disconnect() }
    }
}
