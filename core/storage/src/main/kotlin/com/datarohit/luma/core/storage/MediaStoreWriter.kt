package com.datarohit.luma.core.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Photographic metadata written to EXIF. No location is ever written. */
data class PhotoMetadata(
    val captureTime: ZonedDateTime,
    val exifOrientation: Int,
    val exposureNs: Long,
    val iso: Int,
    val aperture: Float,
    val focalLengthMm: Float,
    val focalLength35mm: Int,
    val digitalZoom: Float,
    val lensModel: String,
    val software: String,
    val description: String,
    val whiteBalanceManual: Boolean,
    val srgb: Boolean = false,
)

data class SavedItem(val uri: Uri, val displayName: String)

/** Destination for finished photos. */
interface PhotoSink {
    fun saveJpeg(bitmap: Bitmap, quality: Int, meta: PhotoMetadata, baseName: String): SavedItem
    /** Stores an already encoded JPEG (e.g. from the camera HAL), adding Luma's EXIF fields but keeping its orientation tag. */
    fun saveEncodedJpeg(jpeg: ByteArray, width: Int, height: Int, meta: PhotoMetadata, baseName: String): SavedItem
    fun saveDng(baseName: String, meta: PhotoMetadata, write: (OutputStream) -> Unit): SavedItem
    /** HEIF (HEVC still) via the platform encoder; orientation is stored, EXIF fields are not. */
    fun saveHeif(bitmap: Bitmap, quality: Int, meta: PhotoMetadata, baseName: String): SavedItem
}

/**
 * Writes Luma output into the shared `DCIM/Luma` collection with `IS_PENDING` so partially written
 * files are never visible. Only Luma's own items are touched; no storage permission is needed.
 */
class MediaStoreWriter(private val context: Context) : PhotoSink {

    private val resolver: ContentResolver get() = context.contentResolver

    override fun saveJpeg(bitmap: Bitmap, quality: Int, meta: PhotoMetadata, baseName: String): SavedItem {
        // Encode to a private temp file first so EXIF can be written with ExifInterface.
        val temp = File(context.noBackupFilesDir, "$baseName.jpg.tmp")
        try {
            encodeJpeg(bitmap, quality, meta, temp)
            return insert("$baseName.jpg", MIME_JPEG, meta) { out -> temp.inputStream().use { it.copyTo(out) } }
        } finally {
            temp.delete()
        }
    }

    override fun saveEncodedJpeg(jpeg: ByteArray, width: Int, height: Int, meta: PhotoMetadata, baseName: String): SavedItem {
        val temp = File(context.noBackupFilesDir, "$baseName.jpg.tmp")
        try {
            temp.writeBytes(jpeg)
            writeExif(temp, meta, width, height, writeOrientation = false)
            return insert("$baseName.jpg", MIME_JPEG, meta) { out -> temp.inputStream().use { it.copyTo(out) } }
        } finally {
            temp.delete()
        }
    }

    override fun saveDng(baseName: String, meta: PhotoMetadata, write: (OutputStream) -> Unit): SavedItem =
        insert("$baseName.dng", MIME_DNG, meta, write)

    override fun saveHeif(bitmap: Bitmap, quality: Int, meta: PhotoMetadata, baseName: String): SavedItem {
        val temp = File(context.noBackupFilesDir, "$baseName.heic.tmp")
        try {
            encodeHeif(bitmap, quality, meta, temp)
            return insert("$baseName.heic", MIME_HEIF, meta) { out -> temp.inputStream().use { it.copyTo(out) } }
        } finally {
            temp.delete()
        }
    }

    private fun insert(displayName: String, mime: String, meta: PhotoMetadata, write: (OutputStream) -> Unit): SavedItem {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$ALBUM")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.Images.ImageColumns.DATE_TAKEN, meta.captureTime.toInstant().toEpochMilli())
            put(MediaStore.Images.ImageColumns.DESCRIPTION, meta.description)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri, "w")?.use(write) ?: error("cannot open output for $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return SavedItem(uri, displayName)
    }

    companion object {
        const val ALBUM = "Luma"
        const val MIME_JPEG = "image/jpeg"
        const val MIME_DNG = "image/x-adobe-dng"
        /** HEVC-coded HEIF. "image/heic" matches the .heic name, so MediaStore keeps it (no ".heic.heif"). */
        const val MIME_HEIF = "image/heic"
        private val EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT)
        private val EXIF_OFFSET = DateTimeFormatter.ofPattern("xxx", Locale.ROOT)

        fun baseName(time: ZonedDateTime): String =
            "LUMA_" + time.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT))

        /** EXIF orientation tag for a clockwise rotation in degrees. */
        fun exifOrientation(degrees: Int): Int = when (((degrees % 360) + 360) % 360) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }

        /** Encodes [bitmap] (its colour space's ICC profile is embedded) and writes EXIF. */
        fun encodeJpeg(bitmap: Bitmap, quality: Int, m: PhotoMetadata, file: File) {
            file.outputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG encode failed" } }
            writeExif(file, m, bitmap.width, bitmap.height, writeOrientation = true)
        }

        fun encodeHeif(bitmap: Bitmap, quality: Int, m: PhotoMetadata, file: File) {
            val rotation = when (m.exifOrientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            val writer = androidx.heifwriter.HeifWriter.Builder(file.absolutePath, bitmap.width, bitmap.height, androidx.heifwriter.HeifWriter.INPUT_MODE_BITMAP)
                .setQuality(quality.coerceIn(1, 100))
                .setRotation(rotation)
                .setMaxImages(1)
                .build()
            try {
                writer.start()
                writer.addBitmap(bitmap)
                writer.stop(10_000)
            } finally {
                writer.close()
            }
        }

        /** Writes Luma's EXIF fields. Never writes location. */
        fun writeExif(file: File, m: PhotoMetadata, width: Int, height: Int, writeOrientation: Boolean) {
            val exif = ExifInterface(file.absolutePath)
            val time = m.captureTime.format(EXIF_TIME)
            exif.setAttribute(ExifInterface.TAG_DATETIME, time)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, time)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, time)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, m.captureTime.format(EXIF_OFFSET))
            if (writeOrientation) exif.setAttribute(ExifInterface.TAG_ORIENTATION, m.exifOrientation.toString())
            exif.setAttribute(ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER)
            exif.setAttribute(ExifInterface.TAG_MODEL, android.os.Build.MODEL)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, m.software)
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, m.description)
            exif.setAttribute("LensMake", android.os.Build.MANUFACTURER)
            exif.setAttribute("LensModel", m.lensModel)
            if (m.exposureNs > 0) exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "%.6f".format(Locale.ROOT, m.exposureNs / 1e9))
            @Suppress("DEPRECATION")
            if (m.iso > 0) exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, m.iso.toString())
            if (m.aperture > 0) exif.setAttribute(ExifInterface.TAG_F_NUMBER, "%.2f".format(Locale.ROOT, m.aperture))
            if (m.focalLengthMm > 0) exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(m.focalLengthMm * 1000).toInt()}/1000")
            if (m.focalLength35mm > 0) exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, m.focalLength35mm.toString())
            if (m.digitalZoom > 1.01f) exif.setAttribute(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, "${(m.digitalZoom * 100).toInt()}/100")
            exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, if (m.whiteBalanceManual) "1" else "0")
            exif.setAttribute(ExifInterface.TAG_COLOR_SPACE, if (m.srgb) "1" else "65535")  // 65535: see embedded ICC profile
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            exif.saveAttributes()
        }
    }
}

/** Writes into an app-private directory (debug validation runs; never touches shared storage). */
class PrivateDirectorySink(private val directory: File) : PhotoSink {
    override fun saveJpeg(bitmap: Bitmap, quality: Int, meta: PhotoMetadata, baseName: String): SavedItem {
        directory.mkdirs()
        val file = File(directory, "$baseName.jpg")
        MediaStoreWriter.encodeJpeg(bitmap, quality, meta, file)
        return SavedItem(Uri.fromFile(file), file.name)
    }

    override fun saveEncodedJpeg(jpeg: ByteArray, width: Int, height: Int, meta: PhotoMetadata, baseName: String): SavedItem {
        directory.mkdirs()
        val file = File(directory, "$baseName.jpg")
        file.writeBytes(jpeg)
        MediaStoreWriter.writeExif(file, meta, width, height, writeOrientation = false)
        return SavedItem(Uri.fromFile(file), file.name)
    }

    override fun saveDng(baseName: String, meta: PhotoMetadata, write: (OutputStream) -> Unit): SavedItem {
        directory.mkdirs()
        val file = File(directory, "$baseName.dng")
        file.outputStream().use(write)
        return SavedItem(Uri.fromFile(file), file.name)
    }

    override fun saveHeif(bitmap: Bitmap, quality: Int, meta: PhotoMetadata, baseName: String): SavedItem {
        directory.mkdirs()
        val file = File(directory, "$baseName.heic")
        MediaStoreWriter.encodeHeif(bitmap, quality, meta, file)
        return SavedItem(Uri.fromFile(file), file.name)
    }
}

/** Luma's own items in the shared collection (visible without storage permission because Luma owns them). */
class LumaMediaRepository(private val context: Context) {

    data class Item(
        val uri: Uri,
        val name: String,
        val mime: String,
        val dateTaken: Long,
        val width: Int,
        val height: Int,
        val size: Long,
        val description: String?,
        val durationMs: Long = 0,
    ) {
        val isVideo: Boolean get() = mime.startsWith("video/")
    }

    /** Luma photos (DCIM/Luma) and videos (Movies/Luma), newest first. */
    fun list(): List<Item> = (images() + videos()).sortedByDescending { it.dateTaken }

    fun videos(): List<Item> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.Video.VideoColumns.DATE_TAKEN, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.SIZE, MediaStore.Video.VideoColumns.DESCRIPTION, MediaStore.Video.VideoColumns.DURATION,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
        val args = arrayOf("${Environment.DIRECTORY_MOVIES}/${MediaStoreWriter.ALBUM}%", context.packageName)
        val out = mutableListOf<Item>()
        context.contentResolver.query(collection, projection, selection, args, "${MediaStore.Video.VideoColumns.DATE_TAKEN} DESC")?.use { c ->
            while (c.moveToNext()) {
                out += Item(
                    uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                    name = c.getString(1) ?: "",
                    mime = c.getString(2) ?: "video/mp4",
                    dateTaken = c.getLong(3),
                    width = c.getInt(4),
                    height = c.getInt(5),
                    size = c.getLong(6),
                    description = c.getString(7),
                    durationMs = c.getLong(8),
                )
            }
        }
        return out
    }

    fun images(): List<Item> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.Images.ImageColumns.DATE_TAKEN, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.SIZE, MediaStore.Images.ImageColumns.DESCRIPTION,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
        val args = arrayOf("${Environment.DIRECTORY_DCIM}/${MediaStoreWriter.ALBUM}%", context.packageName)
        val out = mutableListOf<Item>()
        context.contentResolver.query(collection, projection, selection, args, "${MediaStore.Images.ImageColumns.DATE_TAKEN} DESC")?.use { c ->
            while (c.moveToNext()) {
                out += Item(
                    uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                    name = c.getString(1) ?: "",
                    mime = c.getString(2) ?: "",
                    dateTaken = c.getLong(3),
                    width = c.getInt(4),
                    height = c.getInt(5),
                    size = c.getLong(6),
                    description = c.getString(7),
                )
            }
        }
        return out
    }

    fun delete(uri: Uri): Boolean = context.contentResolver.delete(uri, null, null) > 0
}
