package com.usblan

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileNotFoundException

/**
 * Minimal read-only file provider for handing downloaded update APKs to the
 * system package installer.
 *
 * The Android framework has no public FileProvider (it only exists in
 * AndroidX, and this app takes no dependencies), so this implements the
 * small subset we need. It reads `<cache-path name=".." path=".." />` entries
 * from the `android.support.FILE_PROVIDER_PATHS` meta-data (res/xml/file_paths.xml)
 * and serves URIs of the form `content://<authority>/<name>/<file>`.
 */
class UpdateFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.usblan.fileprovider"
        private const val META_PATHS = "android.support.FILE_PROVIDER_PATHS"
        private const val APK_MIME = "application/vnd.android.package-archive"

        /** Build a content:// URI for [file], which must live under a configured root. */
        fun getUriForFile(context: Context, file: File): Uri {
            val path = file.canonicalPath
            for ((name, root) in loadRoots(context)) {
                val rootPath = root.canonicalPath
                if (path.startsWith(rootPath + File.separator)) {
                    val rel = path.substring(rootPath.length + 1)
                    return Uri.Builder()
                        .scheme("content")
                        .authority(AUTHORITY)
                        .appendPath(name)
                        .appendEncodedPath(Uri.encode(rel, "/"))
                        .build()
                }
            }
            throw IllegalArgumentException("File is not under a configured root: $path")
        }

        private fun loadRoots(context: Context): Map<String, File> {
            val info: ProviderInfo = context.packageManager.resolveContentProvider(
                AUTHORITY, PackageManager.GET_META_DATA
            ) ?: return emptyMap()
            val parser = info.loadXmlMetaData(context.packageManager, META_PATHS)
                ?: return emptyMap()
            val roots = LinkedHashMap<String, File>()
            parser.use { p ->
                var event = p.next()
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && p.name == "cache-path") {
                        val name = p.getAttributeValue(null, "name")
                        val sub = p.getAttributeValue(null, "path") ?: ""
                        if (!name.isNullOrEmpty()) roots[name] = File(context.cacheDir, sub)
                    }
                    event = p.next()
                }
            }
            return roots
        }
    }

    @Volatile private var roots: Map<String, File>? = null

    override fun onCreate(): Boolean = true

    private fun resolve(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("No context")
        val segments = uri.pathSegments
        if (uri.authority != AUTHORITY || segments.size < 2) throw FileNotFoundException(uri.toString())
        val map = roots ?: loadRoots(ctx).also { roots = it }
        val root = map[segments[0]] ?: throw FileNotFoundException(uri.toString())
        val file = File(root, segments.drop(1).joinToString(File.separator)).canonicalFile
        if (!file.path.startsWith(root.canonicalPath + File.separator)) {
            throw SecurityException("Path escapes root")
        }
        return file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read-only provider")
        val file = resolve(uri)
        if (!file.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment?.endsWith(".apk") == true) APK_MIME else "application/octet-stream"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = resolve(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val names = ArrayList<String>()
        val values = ArrayList<Any?>()
        for (c in cols) {
            when (c) {
                OpenableColumns.DISPLAY_NAME -> { names += c; values += file.name }
                OpenableColumns.SIZE -> { names += c; values += file.length() }
            }
        }
        return MatrixCursor(names.toTypedArray(), 1).apply { addRow(values.toTypedArray()) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Read-only provider")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only provider")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Read-only provider")
}
