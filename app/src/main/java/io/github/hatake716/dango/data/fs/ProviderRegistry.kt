package io.github.hatake716.dango.data.fs

import io.github.hatake716.dango.data.net.NetProtocol
import io.github.hatake716.dango.data.net.NetworkProvider
import io.github.hatake716.dango.domain.model.FsPath

/** スキーム → FileSystemProvider の解決（SPEC §8.2: UI はスキームを意識しない） */
class ProviderRegistry(
    private val local: FileSystemProvider,
    private val network: Map<String, NetworkProvider>,
) {
    fun forPath(path: FsPath): FileSystemProvider =
        network[path.scheme] ?: local

    fun networkProvider(scheme: String): NetworkProvider? = network[scheme]

    fun isNetworkScheme(scheme: String): Boolean = scheme in NetProtocol.schemes

    fun invalidateConnection(connId: Long) {
        network.values.forEach { it.invalidate(connId) }
    }
}
