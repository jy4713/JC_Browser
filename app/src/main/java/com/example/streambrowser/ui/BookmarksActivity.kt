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
 * 즐겨찾기 화면: 폴드 탐색/생성/이름변경/삭제 + Netscape HTML 가져오기/낳볶기 (Soul 스타일).
 * 북마크 클릭 시 MainActivity에 URL을 결과로 돌려준다.
 */
class BookmarksActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.example.streambrowser.util.LocaleHelper.wrap(newBase))
    }

    private var parentId = 0L
    private val pathStack = mutableListOf<Pair<Long, String>>()
    private lateinit var adapter: BmAdapter
    private lateinit var txtTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        txtTitle.text = if (pathStack.isEmpty()) "즐겨찾기"
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
        val input = EditText(this).apply { hint = "폴트 이름" }
        AlertDialog.Builder(this)
            .setTitle("새 폴트")
            .setView(input)
            .setPositiveButton("만들기") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    BookmarkRepo.addFolder(this, name, parentId)
                    reload()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showItemMenu(e: BookmarkEntry) {
        val items = arrayOf("이름 변경", "삭제")
        AlertDialog.Builder(this)
            .setTitle(e.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(this).apply { setText(e.title) }
                        AlertDialog.Builder(this)
                            .setTitle("이름 변경")
                            .setView(input)
                            .setPositiveButton("저장") { _, _ ->
                                BookmarkRepo.rename(this, e.id, input.text.toString().trim())
                                reload()
                            }
                            .setNegativeButton("취소", null)
                            .show()
                    }
                    1 -> {
                        BookmarkRepo.remove(this, e.id)
                        reload()
                        Toast.makeText(this, "삭제되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    // ---------------- 가져오기 / 낳볶기 ----------------

    private fun pickImportFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(i, 3) }.onFailure {
            Toast.makeText(this, "파일 선택을 지원하지 않습니다.", Toast.LENGTH_SHORT).show()
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
            runCatching { startActivity(Intent.createChooser(share, "즐겨찾기 낳볶기")) }
            Toast.makeText(this, "저장됨: ${f.absolutePath}", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, "낳볶기 실패", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this, "$n 개 항목을 가져왔습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure {
                Toast.makeText(this, "가져오기 실패", Toast.LENGTH_SHORT).show()
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
                holder.sub.text = "$n 개 항목"
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
