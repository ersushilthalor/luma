package com.datarohit.luma.video

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.FileDescriptor

/** Where a recording is written. */
interface VideoTarget {
    val displayName: String
    val fileDescriptor: FileDescriptor
    /** Publishes the file. Returns its content URI. */
    fun finish(): Uri
    /** Removes a failed or empty recording. */
    fun abort()
}

/** `Movies/Luma` in the shared MediaStore collection, hidden (IS_PENDING) until finished. */
class MediaStoreVideoTarget(private val context: Context, override val displayName: String, dateTakenMs: Long) : VideoTarget {
    private val resolver = context.contentResolver
    private val uri: Uri
    private val pfd: ParcelFileDescriptor

    init {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.Video.VideoColumns.DATE_TAKEN, dateTakenMs)
        }
        uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("MediaStore insert failed")
        pfd = resolver.openFileDescriptor(uri, "rw") ?: error("cannot open $uri")
    }

    override val fileDescriptor: FileDescriptor get() = pfd.fileDescriptor

    override fun finish(): Uri {
        pfd.close()
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    override fun abort() {
        runCatching { pfd.close() }
        runCatching { resolver.delete(uri, null, null) }
    }

    companion object {
        const val ALBUM = "Luma"
    }
}

/** A document inside a folder the user picked with the Storage Access Framework (e.g. a USB SSD). */
class SafVideoTarget(private val context: Context, treeUri: Uri, override val displayName: String) : VideoTarget {
    private val resolver = context.contentResolver
    private val uri: Uri
    private val pfd: ParcelFileDescriptor

    init {
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        uri = DocumentsContract.createDocument(resolver, parent, "video/mp4", displayName.removeSuffix(".mp4"))
            ?: error("Cannot create a file on the selected storage")
        pfd = resolver.openFileDescriptor(uri, "rw") ?: error("cannot open $uri")
    }

    override val fileDescriptor: FileDescriptor get() = pfd.fileDescriptor

    override fun finish(): Uri {
        pfd.close()
        return uri
    }

    override fun abort() {
        runCatching { pfd.close() }
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }
}

object StorageInfo {
    /**
     * Free bytes on the recording destination: primary storage, or the volume behind a SAF tree
     * (document IDs of removable volumes start with the volume UUID).
     */
    fun freeBytes(context: Context, treeUri: Uri?): Long? = runCatching {
        if (treeUri == null) return@runCatching StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val volumeId = docId.substringBefore(':')
        val sm = context.getSystemService(StorageManager::class.java)
        val dir: File? = sm.storageVolumes.firstOrNull { v ->
            (v.uuid != null && v.uuid.equals(volumeId, ignoreCase = true)) || (v.isPrimary && volumeId == "primary")
        }?.directory
        dir?.let { StatFs(it.path).availableBytes }
    }.getOrNull()

    /** Human name of the SAF destination's volume, if known. */
    fun volumeLabel(context: Context, treeUri: Uri?): String {
        if (treeUri == null) return "Internal"
        val volumeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri).substringBefore(':') }.getOrNull()
        val sm = context.getSystemService(StorageManager::class.java)
        val v = sm.storageVolumes.firstOrNull { it.uuid != null && it.uuid.equals(volumeId, ignoreCase = true) }
        return when {
            volumeId == "primary" -> "Internal (folder)"
            v != null -> v.getDescription(context)
            else -> "External"
        }
    }
}
