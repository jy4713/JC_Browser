package com.example.streambrowser.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/**
 * Final save routing for completed downloads.
 * - public: copy into shared Download/JC Browser (MediaStore, API 29+)
 * - custom: copy into the user-picked SAF folder (dl_folder_tree)
 * - app:    keep in the app-private staging folder
 */
object DownloadFolder {

    fun export(ctx: Context, src: File, mime: String) {
        val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        when (sp.getString("dl_folder", "public")) {
            "custom" -> copyToTree(ctx, sp.getString("dl_folder_tree", null), src, mime)
            else -> copyToPublicDownloads(ctx, src, mime)
        }
    }

    private fun copyToPublicDownloads(ctx: Context, src: File, mime: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/JC Browser")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
        }
    }

    private fun copyToTree(ctx: Context, tree: String?, src: File, mime: String) {
        if (tree.isNullOrEmpty()) return
        runCatching {
            val treeUri = Uri.parse(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val child = runCatching {
                DocumentsContract.createDocument(ctx.contentResolver, parent, mime, src.name)
            }.getOrNull() ?: return
            ctx.contentResolver.openOutputStream(child)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
        }
    }
}
