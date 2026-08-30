package io.github.hatake716.dango.data.fs.local

import android.os.Environment
import io.github.hatake716.dango.domain.model.FsPath

/**
 * ローカルストレージの既知の場所を FsPath として提供する。
 * UI層が java.io.File を直接触らないための入口（CLAUDE.md ルール2）。
 */
object LocalLocations {

    fun internalStorage(): FsPath =
        LocalFileSystemProvider.fromAbsolutePath(
            Environment.getExternalStorageDirectory().absolutePath,
        )

    fun downloads(): FsPath = publicDir(Environment.DIRECTORY_DOWNLOADS)

    fun documents(): FsPath = publicDir(Environment.DIRECTORY_DOCUMENTS)

    fun pictures(): FsPath = publicDir(Environment.DIRECTORY_PICTURES)

    fun movies(): FsPath = publicDir(Environment.DIRECTORY_MOVIES)

    fun music(): FsPath = publicDir(Environment.DIRECTORY_MUSIC)

    private fun publicDir(type: String): FsPath =
        LocalFileSystemProvider.fromAbsolutePath(
            Environment.getExternalStoragePublicDirectory(type).absolutePath,
        )
}
