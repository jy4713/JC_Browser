package com.example.streambrowser.download

import java.io.File

enum class DlStatus { PENDING, RUNNING, DONE, FAILED, CANCELED }

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

/** 다운로드 상태 저장소 (앱 전역) */
object DownloadStore {
    private val map = LinkedHashMap<Long, DlItem>()

    @Volatile
    var listener: (() -> Unit)? = null

    val items: List<DlItem>
        @Synchronized get() = map.values.toList()

    @Synchronized
    fun upsert(item: DlItem) {
        map[item.id] = item
        listener?.invoke()
    }

    @Synchronized
    fun get(id: Long): DlItem? = map[id]

    @Synchronized
    fun remove(id: Long) {
        map.remove(id)
        listener?.invoke()
    }

    @Synchronized
    fun hasRunning(): Boolean =
        map.values.any { it.status == DlStatus.PENDING || it.status == DlStatus.RUNNING }
}
