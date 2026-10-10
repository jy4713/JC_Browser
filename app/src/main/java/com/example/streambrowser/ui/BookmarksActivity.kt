package com.example.streambrowser.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.streambrowser.R
import com.example.streambrowser.db.BookmarkEntry
import com.example.streambrowser.db.BookmarkRepo
import java.io.File

/**
 * 즐겨찾기 화면: 폴드 탐색/생성/이름변경/삭제 + Netscape HTML 가져오기/내보내기 (Soul 스타일).
 * 북마크 클릭 시 MainActivity에 URL을 결과로 돌려준다.
 */
class BookmarksActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.example.streambrowser.util.LocaleHelper.wrap(com.example.streambrowser.util.ThemeHelper.wrap(newBase)))
    }

    private var parentId = 0L
    private val pathStack = mutableListOf<Pair<Long, String>>()
    private lateinit var adapter: BmAdapter
    private lateinit var txtTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.streambrowser.util.ThemeHelper.apply(this)
        setContentView(R.layout.activity_bookmarks)
        txtTitle = findViewById(R.id.txtTitle)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { goUp() }
        findViewById<View>(R.id.btnNewFolder).setOnClickListener { showNewFolderDialog() }
        findViewById<View>(R.id.btnImport).setOnClickListener { pickImportFile() }
        findViewById<View>(R.id.btnExport).setOnClickListener { exportBookmarks() }

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = BmAdapter(
            onClick = { e ->
                if (e.isFolder) enterFolder(e)
                else {
                    setResult(RESULT_OK, Intent().putExtra("url", e.url))
                    finish()
                }
            },
            onLongClick = { e -> showItemMenu(e) }
        )
        list.adapter = adapter
        reload()
    }

    private fun reload() {
        adapter.submit(BookmarkRepo.list(this, parentId))
        txtTitle.text = if (pathStack.isEmpty()) getString(R.string.menu_bookmarks)
        else pathStack.joinToString(" › ") { it.second }
    }

    private fun enterFolder(e: BookmarkEntry) {
        pathStack.add(e.id to e.title)
        parentId = e.id
        reload()
    }

    private fun goUp() {
        if (pathStack.isEmpty()) { finish(); return }
        parentId = pathStack.last().first
        pathStack.removeAt(pathStack.size - 1)
        if (pathStack.isEmpty()) parentId = 0
        else parentId = pathStack.last().first
        // 현재 parent는 pathStack의 마지막 id (비어있으면 0)
        reload()
    }

    private fun showNewFolderDialog() {
        val input = EditText(this).apply { hint = getString(R.string.bookmark_folder_name) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.bookmark_new_folder))
            .setView(com.example.streambrowser.ui.JcUi.fieldBox(this, input))
            .setPositiveButton(getString(R.string.action_create)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    BookmarkRepo.addFolder(this, name, parentId)
                    reload()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showItemMenu(e: BookmarkEntry) {
        val items = arrayOf(getString(R.string.common_rename), getString(R.string.btn_delete))
        AlertDialog.Builder(this)
            .setTitle(e.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(this).apply { setText(e.title) }
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.common_rename))
                            .setView(com.example.streambrowser.ui.JcUi.fieldBox(this, input))
                            .setPositiveButton(getString(R.string.common_save)) { _, _ ->
                                BookmarkRepo.rename(this, e.id, input.text.toString().trim())
                                reload()
                            }
                            .setNegativeButton(getString(R.string.btn_cancel), null)
                            .show()
                    }
                    1 -> {
                        BookmarkRepo.remove(this, e.id)
                        reload()
                        com.example.streambrowser.util.JcToast.show(this, getString(R.string.common_deleted))
                    }
                }
            }
            .show()
    }

    // ---------------- 가져오기 / 내보내기 ----------------

    private fun pickImportFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(i, 3) }.onFailure {
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.bookmark_no_picker))
        }
    }

    private fun exportBookmarks() {
        runCatching {
            val html = BookmarkRepo.exportHtml(this)
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
            dir.mkdirs()
            val f = File(dir, "streambrowser_bookmarks.html")
            f.writeText(html)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { startActivity(Intent.createChooser(share, getString(R.string.bookmark_exported))) }
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.bookmark_export_saved, f.absolutePath), long = true)
        }.onFailure {
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.bookmark_export_failed))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3 && resultCode == RESULT_OK) {
            runCatching {
                data?.data?.let { uri ->
                    contentResolver.openInputStream(uri)?.use { input ->
                        val html = input.readBytes().toString(Charsets.UTF_8)
                        val n = BookmarkRepo.importHtml(this, html)
                        reload()
                        com.example.streambrowser.util.JcToast.show(this, getString(R.string.bookmark_imported, n))
                    }
                }
            }.onFailure {
                com.example.streambrowser.util.JcToast.show(this, getString(R.string.bookmark_import_failed))
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pathStack.isNotEmpty()) goUp() else super.onBackPressed()
    }

    // ---------------- 어댑터 ----------------

    class BmAdapter(
        private val onClick: (BookmarkEntry) -> Unit,
        private val onLongClick: (BookmarkEntry) -> Unit
    ) : RecyclerView.Adapter<BmAdapter.VH>() {

        private var entries = listOf<BookmarkEntry>()

        fun submit(list: List<BookmarkEntry>) {
            entries = list
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.rowIcon)
            val title: TextView = v.findViewById(R.id.rowTitle)
            val sub: TextView = v.findViewById(R.id.rowSub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

        override fun getItemCount() = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = entries[position]
            holder.title.text = e.title.ifEmpty { e.url }
            if (e.isFolder) {
                holder.icon.setImageResource(R.drawable.ic_folder)
                holder.icon.setColorFilter(0xFFF9AB00.toInt())
                val n = BookmarkRepo.childCount(holder.itemView.context, e.id)
                holder.sub.text = holder.itemView.context.getString(R.string.folder_items, n)
            } else {
                holder.icon.setImageResource(R.drawable.ic_bookmark)
                holder.icon.setColorFilter(0xFF1A73E8.toInt())
                holder.sub.text = e.url
            }
            holder.itemView.setOnClickListener { onClick(e) }
            holder.itemView.setOnLongClickListener { onLongClick(e); true }
        }
    }
}
