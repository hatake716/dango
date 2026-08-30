package io.github.hatake716.dango

import android.app.Application
import android.content.Context
import android.os.Environment
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.video.VideoFrameDecoder
import io.github.hatake716.dango.data.archive.ArchiveManager
import io.github.hatake716.dango.data.db.DangoDatabase
import io.github.hatake716.dango.data.fs.ProviderRegistry
import io.github.hatake716.dango.data.net.CredentialStore
import io.github.hatake716.dango.data.net.NetPreviewCache
import io.github.hatake716.dango.data.net.NetProtocol
import io.github.hatake716.dango.data.net.NetworkDeps
import io.github.hatake716.dango.data.net.NetworkProvider
import io.github.hatake716.dango.data.fs.FileSystemProvider
import io.github.hatake716.dango.data.fs.local.LocalFileSystemProvider
import io.github.hatake716.dango.data.fs.trash.TrashManager
import io.github.hatake716.dango.data.info.InfoLoader
import io.github.hatake716.dango.data.prefs.SettingsRepository
import io.github.hatake716.dango.data.text.TextFileStore
import io.github.hatake716.dango.data.transfer.TransferManager

/**
 * 手動 DI のコンテナ。
 * SPEC §2 の Hilt は未導入（導入タイミングは SPEC §15 の確認待ち項目。docs/PROGRESS.md 参照）。
 */
class AppContainer(context: Context) {
    val settingsRepository = SettingsRepository(context)
    val fileSystemProvider: FileSystemProvider = LocalFileSystemProvider()
    val database = DangoDatabase.build(context)
    val trashManager = TrashManager(
        internalRootPath = Environment.getExternalStorageDirectory().absolutePath,
        dao = database.trashDao(),
    )
    val credentialStore = CredentialStore(context)
    val sessionPasswords = mutableMapOf<Long, String>()
    private val networkDeps = NetworkDeps(database.connectionDao(), credentialStore, sessionPasswords)
    val providerRegistry = ProviderRegistry(
        local = fileSystemProvider,
        network = NetProtocol.entries.associate { protocol ->
            protocol.scheme to NetworkProvider(protocol, networkDeps)
        },
    )
    val netPreviewCache = NetPreviewCache(context.cacheDir)
    val transferManager = TransferManager(providerRegistry)
    val archiveManager = ArchiveManager(context.cacheDir)
    val textFileStore = TextFileStore()
    val infoLoader = InfoLoader()
}

class DangoApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // sshj（SFTP）が要求する BouncyCastle を Android 同梱の縮小版から差し替える
        runCatching {
            java.security.Security.removeProvider("BC")
            java.security.Security.insertProviderAt(
                org.bouncycastle.jce.provider.BouncyCastleProvider(),
                1,
            )
        }
        container = AppContainer(this)
    }

    /** サムネイル用 ImageLoader（SPEC §6.5: ディスクキャッシュ最大512MB、GIF/SVG/動画フレーム対応） */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(AnimatedImageDecoder.Factory())
                add(SvgDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("thumbnails"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
