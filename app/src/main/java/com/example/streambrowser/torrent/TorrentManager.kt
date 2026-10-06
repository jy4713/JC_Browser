package com.example.streambrowser.torrent

import android.content.Context
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File

/**
 * 토렌트 순차 다운로드/재생 관리자 (Tincat과 동일 엔진: libtorrent4j)
 * - "바로 재생": 영상 파일 하나만 우선순위 TOP + 순차 다운로드(setSequentialRange 0~끝)
 *   → 앞 부분부터 받으므로 다운로드 중인 파일을 플레이어가 즉시 재생 가능
 * - "모두 다운로드": 전체 파일 일반 다운로드
 */
object TorrentManager {

    private var session: SessionManager? = null
    private val sessionLock = Any()

    /** 진행 중인 토렌트 (infoHash hex → 핸들) */
    private val active = HashMap<String, TorrentHandle>()

    val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "mpg", "mpeg", "flv", "wmv", "3gp", "rmvb")

    class Job(
        val info: TorrentInfo,
        val handle: TorrentHandle,
        /** 바로 재생 대상 파일 인덱스 (모두 다운로드면 -1) */
        val streamFileIndex: Int,
        val saveDir: File
    ) {
        val name: String get() = info.name()
        fun streamFile(): File? =
            if (streamFileIndex < 0) null
            else File(saveDir, info.files().filePath(streamFileIndex))
    }

    @Synchronized
    private fun ensureSession(): SessionManager {
        session?.let { return it }
        val s = SessionManager()
        s.start()
        // libtorrent4j는 start()만으로는 DHT가 켜지지 않음 — 마그넷 메타데이터 수신에 DHT 필수
        runCatching { if (!s.isDhtRunning) s.startDht() }
        // DHT 부트스트랩(노드 연결)이 될 때까지 잠시 대기
        for (i in 0 until 30) {
            val nodes = runCatching { s.dhtNodes() }.getOrDefault(0)
            if (nodes > 0) break
            Thread.sleep(500)
        }
        session = s
        return s
    }

    fun isVideoFile(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTS
    }

    /** 가장 큰 영상 파일 인덱스 (없으면 -1) */
    fun largestVideoIndex(ti: TorrentInfo): Int {
        val fs = ti.files()
        var best = -1
        var bestSize = -1L
        for (i in 0 until fs.numFiles()) {
            val p = fs.filePath(i)
            if (!isVideoFile(p)) continue
            val sz = fs.fileSize(i)
            if (sz > bestSize) { bestSize = sz; best = i }
        }
        return best
    }

    fun infoHashHex(ti: TorrentInfo): String = ti.infoHash().toString()

    /**
     * 토렌트 추가. 이미 활성 중이면 기존 Job 반환.
     * @param stream true: 영상 하나만 순차 다운로드(재생용) / false: 전체 일반 다운로드
     */
    @Synchronized
    fun add(ti: TorrentInfo, saveDir: File, stream: Boolean): Job {
        ensureSession()
        val key = infoHashHex(ti)
        active[key]?.let { return Job(ti, it, if (stream) largestVideoIndex(ti) else -1, saveDir) }

        val fs = ti.files()
        val priorities = Array(fs.numFiles()) { Priority.IGNORE }
        val streamIdx = if (stream) largestVideoIndex(ti) else -1
        if (stream && streamIdx >= 0) {
            priorities[streamIdx] = Priority.TOP_PRIORITY
        } else {
            for (i in priorities.indices) priorities[i] = Priority.DEFAULT
        }

        val s = session!!
        saveDir.mkdirs()
        s.download(ti, saveDir, null, priorities, null, torrent_flags_t())

        // 비동기 추가 + 세션 초기화가 늦어질 수 있어 핸들이 잡힐 때까지 충분히 대기
        var h: TorrentHandle? = null
        for (i in 0 until 100) {
            h = s.find(ti.infoHash())
            if (h != null) break
            Thread.sleep(100)
        }
        val handle = h ?: error("torrent handle not found")

        // 일부 환경에서 paused 상태로 추가되는 경우 대비
        runCatching { handle.resume() }

        if (stream && streamIdx >= 0) {
            // 앞에서부터 순서대로 받기 → 플레이어가 중간부터 재생 가능
            runCatching { handle.setSequentialRange(0, ti.numPieces() - 1) }
        }
        active[key] = handle
        return Job(ti, handle, streamIdx, saveDir)
    }

    /**
     * 마그넷 메타데이터만 조회 (파일 목록 확인용). 블로킹 — IO 스레드에서.
     * @return null 이면 메타데이터 수신 실패(시드 없음/시간 초과)
     */
    fun fetchMagnetInfo(magnet: String, timeoutSec: Int = 60, workDir: File): TorrentInfo? {
        val s = ensureSession()
        val tmp = File(workDir, ".magnet")
        tmp.mkdirs()
        val bytes = runCatching { s.fetchMagnet(magnet, timeoutSec, tmp) }.getOrNull()
            ?: return null
        return TorrentInfo(bytes)
    }

    /**
     * 마그넷 추가: 메타데이터를 먼저 받아 파일 목록을 확인한 뒤 add()로 등록.
     * 블로킹 호출이므로 반드시 IO 스레드에서.
     */
    fun addMagnet(magnet: String, saveDir: File, stream: Boolean, timeoutSec: Int = 60): Job? {
        val ti = fetchMagnetInfo(magnet, timeoutSec, saveDir) ?: return null
        return add(ti, saveDir, stream)
    }

    fun find(ti: TorrentInfo): TorrentHandle? = session?.find(ti.infoHash())

    @Synchronized
    fun remove(key: String) {
        active.remove(key)?.let { h ->
            runCatching { session?.remove(h) }
        }
    }

    @Synchronized
    fun stopAll() {
        active.clear()
        session?.stop()
        session = null
    }

    /** 다운로드 중인 파일이 디스크에 생길 때까지 대기 (플레이어 실행 전 호출, IO 스레드) */
    fun awaitFileReady(f: File?, maxSec: Int = 30): Boolean {
        if (f == null) return false
        for (i in 0 until maxSec * 2) {
            if (f.exists() && f.length() > 0) return true
            Thread.sleep(500)
        }
        return f.exists()
    }
}
