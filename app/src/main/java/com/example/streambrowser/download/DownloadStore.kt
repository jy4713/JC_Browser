package com.example.streambrowser.download

import android.content.Context
import org.json.JSONArray
import java.io.File

enum class DlStatus { PENDING, RUNNING, PAUSED, DONE, FAILED, CANCELED }

data class DlItem(
    val id: Long,
    val url: String,
    val page: String,
    val kind: String,
    val name: String,
    var ext: String = "mp4",
    var status: DlStatus = DlStatus.PENDING,
    var doneBytes: Long = 0,
    var totalDurationMs: Long = -1,
    var file: File? = null,
    var currentTimeMs: Long = 0,
    var speedBps: Long = 0
)

/** 다운로드 상태 저장소 (앱 전역). 완료/취소/실패 항목은 SharedPreferences에 영속화 */
object DownloadStore {
    private const val PREFS = "dl_store"
    private const val KEY_HISTORY = "history"

    private val map = LinkedHashMap<Long, DlItem>()
    private var appCtx: Context? = null

    @Volatile
    var listener: (() -> Unit)? = null

    val items: List<DlItem>
        @Synchronized get() = map.values.toList()

    /** 앱 시작 시 1회 호출: 지난 다운로드 이력 로드 */
    @Synchronized
    fun init(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        runCatching {
            val sp = appCtx!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(sp.getString(KEY_HISTORY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val item = DlItem(
                    id = o.getLong("id"),
                    url = o.getString("url"),
                    page = o.optString("page"),
                    kind = o.optString("kind"),
                    name = o.optString("name"),
                    ext = o.optString("ext", "mp4"),
                    status = runCatching { DlStatus.valueOf(o.optString("status", "DONE")) }
                        .getOrDefault(DlStatus.DONE),
                    doneBytes = o.optLong("doneBytes"),
                    file = o.optString("file").takeIf { it.isNotEmpty() }?.let { File(it) }
                )
                map[item.id] = item
            }
        }
    }

    @Synchronized
    fun upsert(item: DlItem) {
        map[item.id] = item
        listener?.invoke()
        // 진행 중 상태는 매우 잦아 영속화 생략, 최종 상태만 저장
        if (item.status != DlStatus.PENDING && item.status != DlStatus.RUNNING) persist()
    }

    @Synchronized
    fun get(id: Long): DlItem? = map[id]

    /** 같은 URL의 가장 최근 다운로드 항목 (리스트 버튼 진행률 표시용) */
    @Synchronized
    fun byUrl(url: String): DlItem? =
        map.values.filter { it.url == url }.maxByOrNull { it.id }

    @Synchronized
    fun remove(id: Long) {
        map.remove(id)
        listener?.invoke()
        persist()
    }

    @Synchronized
    fun hasRunning(): Boolean =
        map.values.any { it.status == DlStatus.PENDING || it.status == DlStatus.RUNNING }

    @Synchronized
    private fun persist() {
        val ctx = appCtx ?: return
        runCatching {
            val arr = JSONArray()
            map.values
                .filter { it.status == DlStatus.DONE || it.status == DlStatus.FAILED || it.status == DlStatus.CANCELED }
                .forEach { item ->
                    arr.put(org.json.JSONObject().apply {
                        put("id", item.id)
                        put("url", item.url)
                        put("page", item.page)
                        put("kind", item.kind)
                        put("name", item.name)
                        put("ext", item.ext)
                        put("status", item.status.name)
                        put("doneBytes", item.doneBytes)
                        put("file", item.file?.absolutePath ?: "")
                    })
                }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_HISTORY, arr.toString()).apply()
        }
    }
}
