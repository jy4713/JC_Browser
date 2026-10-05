package com.example.streambrowser.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 북마크(폴트 지원)/방문기록 저장 DB */
class BrowserDb(context: Context) :
    SQLiteOpenHelper(context, "browser.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS bookmarks(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "parent_id INTEGER DEFAULT 0, " +
                    "is_folder INTEGER DEFAULT 0, " +
                    "title TEXT, url TEXT, time INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS history(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "title TEXT, url TEXT, time INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "CREATE TABLE bookmarks_v2(" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "parent_id INTEGER DEFAULT 0, " +
                        "is_folder INTEGER DEFAULT 0, " +
                        "title TEXT, url TEXT, time INTEGER)"
            )
            db.execSQL("INSERT INTO bookmarks_v2(id, parent_id, is_folder, title, url, time) SELECT id, 0, 0, title, url, time FROM bookmarks")
            db.execSQL("DROP TABLE bookmarks")
            db.execSQL("ALTER TABLE bookmarks_v2 RENAME TO bookmarks")
        }
    }

    companion object {
        @Volatile
        private var instance: BrowserDb? = null
        fun get(context: Context): BrowserDb =
            instance ?: synchronized(this) {
                instance ?: BrowserDb(context.applicationContext).also { instance = it }
            }
    }
}

data class BookmarkEntry(
    val id: Long,
    val parentId: Long,
    val isFolder: Boolean,
    val title: String,
    val url: String
)

object BookmarkRepo {

    fun add(context: Context, title: String, url: String, parentId: Long = 0): Long {
        val v = ContentValues().apply {
            put("parent_id", parentId)
            put("is_folder", 0)
            put("title", title)
            put("url", url)
            put("time", System.currentTimeMillis())
        }
        return BrowserDb.get(context).writableDatabase.insert("bookmarks", null, v)
    }

    fun addFolder(context: Context, title: String, parentId: Long = 0): Long {
        val v = ContentValues().apply {
            put("parent_id", parentId)
            put("is_folder", 1)
            put("title", title)
            put("url", "")
            put("time", System.currentTimeMillis())
        }
        return BrowserDb.get(context).writableDatabase.insert("bookmarks", null, v)
    }

    /** 폴트 먼저 → 즐겨찾기 (제목 순) */
    fun list(context: Context, parentId: Long): List<BookmarkEntry> {
        val c = BrowserDb.get(context).readableDatabase.rawQuery(
            "SELECT id, parent_id, is_folder, title, url FROM bookmarks WHERE parent_id=? ORDER BY is_folder DESC, time DESC",
            arrayOf(parentId.toString())
        )
        val out = mutableListOf<BookmarkEntry>()
        while (c.moveToNext()) {
            out.add(
                BookmarkEntry(
                    c.getLong(0), c.getLong(1), c.getInt(2) == 1, c.getString(3) ?: "", c.getString(4) ?: ""
                )
            )
        }
        c.close()
        return out
    }

    fun rename(context: Context, id: Long, title: String) {
        val v = ContentValues().apply { put("title", title) }
        BrowserDb.get(context).writableDatabase.update("bookmarks", v, "id=?", arrayOf(id.toString()))
    }

    /** 폴트 삭제 시 하위 항목도 재귀 삭제 */
    fun remove(context: Context, id: Long) {
        val db = BrowserDb.get(context).writableDatabase
        val c = db.rawQuery("SELECT id FROM bookmarks WHERE parent_id=?", arrayOf(id.toString()))
        val children = mutableListOf<Long>()
        while (c.moveToNext()) children.add(c.getLong(0))
        c.close()
        children.forEach { remove(context, it) }
        db.delete("bookmarks", "id=?", arrayOf(id.toString()))
    }

    fun childCount(context: Context, parentId: Long): Int {
        val c = BrowserDb.get(context).readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM bookmarks WHERE parent_id=?", arrayOf(parentId.toString())
        )
        var n = 0
        if (c.moveToFirst()) n = c.getInt(0)
        c.close()
        return n
    }

    // ---------------- Netscape Bookmark HTML 낳볶기 ----------------

    fun exportHtml(context: Context): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        sb.append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
        appendFolder(sb, context, 0, 1)
        sb.append("</DL><p>\n")
        return sb.toString()
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun appendFolder(sb: StringBuilder, context: Context, parentId: Long, depth: Int) {
        val indent = "    ".repeat(depth)
        for (e in list(context, parentId)) {
            if (e.isFolder) {
                sb.append("$indent<DT><H3>${esc(e.title)}</H3>\n")
                sb.append("$indent<DL><p>\n")
                appendFolder(sb, context, e.id, depth + 1)
                sb.append("$indent</DL><p>\n")
            } else {
                sb.append("$indent<DT><A HREF=\"${esc(e.url)}\">${esc(e.title)}</A>\n")
            }
        }
    }

    /** Netscape 형식 HTML 파싱 후 가져오기, 가져온 항목 수 반환 */
    fun importHtml(context: Context, html: String): Int {
        var count = 0
        val stack = ArrayDeque<Long>()
        stack.addLast(0L)
        var pendingFolder: Long? = null
        val h3Re = Regex("<H3[^>]*>(.*?)</H3>", RegexOption.IGNORE_CASE)
        val aRe = Regex("<A\\s+[^>]*HREF=\"([^\"]*)\"[^>]*>(.*?)</A>", RegexOption.IGNORE_CASE)
        val unesc: (String) -> String = {
            it.replace("&quot;", "\"").replace("&gt;", ">").replace("&lt;", "<")
                .replace("&amp;", "&")
        }
        for (line in html.lines()) {
            val l = line.trim()
            if (l.startsWith("<DL", true)) {
                if (pendingFolder != null) {
                    stack.addLast(pendingFolder)
                    pendingFolder = null
                }
            } else if (l.startsWith("</DL", true)) {
                if (stack.size > 1) stack.removeLast()
            }
            val h3 = h3Re.find(l)
            if (h3 != null) {
                pendingFolder = addFolder(context, unesc(h3.groupValues[1]).trim(), stack.last())
                count++
                continue
            }
            val a = aRe.find(l)
            if (a != null) {
                val url = unesc(a.groupValues[1])
                if (url.startsWith("http")) {
                    add(context, unesc(a.groupValues[2]).trim().ifEmpty { url }, url, stack.last())
                    count++
                }
            }
        }
        return count
    }
}

object HistoryRepo {
    fun add(context: Context, title: String, url: String) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return
        val v = ContentValues().apply {
            put("title", title)
            put("url", url)
            put("time", System.currentTimeMillis())
        }
        runCatching {
            val db = BrowserDb.get(context).writableDatabase
            db.insert("history", null, v)
            // 크롬처럼 오래된 기록 자동 정리 (최신 3000걸만 유지 → DB 무한 증가/저장 공간 방지)
            db.execSQL(
                "DELETE FROM history WHERE id NOT IN " +
                        "(SELECT id FROM history ORDER BY time DESC LIMIT 3000)"
            )
        }
    }

    fun all(context: Context): List<Triple<String, String, Long>> {
        val c = BrowserDb.get(context).readableDatabase.rawQuery(
            "SELECT title, url, time FROM history ORDER BY time DESC LIMIT 500", null
        )
        val out = mutableListOf<Triple<String, String, Long>>()
        while (c.moveToNext()) out.add(Triple(c.getString(0) ?: "", c.getString(1) ?: "", c.getLong(2)))
        c.close()
        return out
    }

    fun remove(context: Context, url: String) {
        BrowserDb.get(context).writableDatabase.delete("history", "url=?", arrayOf(url))
    }

    fun clear(context: Context) {
        BrowserDb.get(context).writableDatabase.delete("history", null, null)
    }
}
