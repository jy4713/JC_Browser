package com.example.streambrowser.torrent

import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File

/**
 * 토렌트 다운로드 관리자 (libtorrent4j)
 * - .torrent / 마그넷 모두 "전체 파일 다운로드" 방식 (받으면서 재생 기능은 제거됨)
 * - 모든 Job(진행중/완료/취소)을 목록으로 유지 → TorrentDownloadsActivity에서 관리
 * - 완료 시 onJobDone 콜백으로 설정된 다운로드 폴더에 납품 (DownloadFolder)
 */
object TorrentManager {

    enum class State { RUNNING, PAUSED, DONE, CANCELLED, FAILED }

    class Job(
        val info: TorrentInfo,
        val handle: TorrentHandle,
        val saveDir: File
    ) {
        val key: String get() = info.infoHash().toString()
        val name: String get() = info.name()
        val totalSize: Long get() = info.totalSize()

        @Volatile var state: State = State.RUNNING
        @Volatile var progress: Float = 0f
        @Volatile var downRate: Int = 0   // B/s
        @Volatile var upRate: Int = 0     // B/s
        @Volatile var doneBytes: Long = 0
        @Volatile var seeds: Int = 0
        @Volatile var peers: Int = 0
        @Volatile var error: String? = null
        @Volatile var exported: Boolean = false

        /** 완료된 파일들 (저장 폴더 기준) */
        fun files(): List<File> {
            val fs = info.files()
            val base = File(saveDir, fs.filePath(0)).parentFile ?: return emptyList()
            return (0 until fs.numFiles())
                .map { File(saveDir, fs.filePath(it)) }
                .filter { it.isFile }
                .ifEmpty { base.walkTopDown().filter { it.isFile }.toList() }
        }
    }

    private var session: SessionManager? = null
    private val sessionLock = Any()

    /** 전체 Job 목록 (추가 순) — 관리 화면에서 표시 */
    private val jobs = LinkedHashMap<String, Job>()

    /** 완료 시 호출 (모니터 스레드에서) — 납품/토스트 처리는 콜백 측에서 */
    @Volatile
    var onJobDone: ((Job) -> Unit)? = null

    @Volatile
    var onJobFailed: ((Job) -> Unit)? = null

    private var pendingDlLimitKb = 0
    private var pendingUlLimitKb = 0

    val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "mpg", "mpeg", "flv", "wmv", "3gp", "rmvb")

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
        // 저장해 둔 속도 제한 적용
        runCatching {
            val sp = SettingsPack()
            if (pendingDlLimitKb > 0) sp.downloadRateLimit(pendingDlLimitKb * 1024)
            if (pendingUlLimitKb > 0) sp.uploadRateLimit(pendingUlLimitKb * 1024)
            s.applySettings(sp)
        }
        session = s
        return s
    }

    /** 다운/업로드 속도 제한 (KB/s, 0=무제한) — 세션 없으면 저장만 항둡 */
    @Synchronized
    fun applyRateLimits(dlKb: Int, ulKb: Int) {
        pendingDlLimitKb = dlKb.coerceAtLeast(0)
        pendingUlLimitKb = ulKb.coerceAtLeast(0)
        session?.let { s ->
            runCatching {
                val sp = SettingsPack()
                if (pendingDlLimitKb > 0) sp.downloadRateLimit(pendingDlLimitKb * 1024) else sp.downloadRateLimit(0)
                if (pendingUlLimitKb > 0) sp.uploadRateLimit(pendingUlLimitKb * 1024) else sp.uploadRateLimit(0)
                s.applySettings(sp)
            }
        }
    }

    fun isVideoFile(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTS
    }

    fun infoHashHex(ti: TorrentInfo): String = ti.infoHash().toString()

    /** 진행 중(RUNNING/PAUSED) Job 개수 */
    @Synchronized
    fun activeCount(): Int = jobs.values.count { it.state == State.RUNNING || it.state == State.PAUSED }

    /** 관리 화면 표용 전체 목록 (최신 순) */
    @Synchronized
    fun listJobs(): List<Job> = jobs.values.toList().reversed()

    @Synchronized
    fun findJob(key: String): Job? = jobs[key]

    /**
     * 토렌트 추가 (전체 파일 다운로드).
     * @param maxActive 동시 다운로드 상한 — 초과 시 IllegalStateException
     */
    @Synchronized
    fun add(ti: TorrentInfo, saveDir: File, maxActive: Int = Int.MAX_VALUE): Job {
        jobs[infoHashHex(ti)]?.let { existing ->
            // 취소/완료된 같은 토렌트를 다시 추가하면 기존 항목 재사용(파일 이어 받기)
            if (existing.state == State.CANCELLED || existing.state == State.FAILED) {
                resumeJob(existing.key)
            }
            return existing
        }
        ensureSession()
        val running = jobs.values.count { it.state == State.RUNNING }
        if (running >= maxActive) throw IllegalStateException("max_active")

        val fs = ti.files()
        val priorities = Array(fs.numFiles()) { Priority.DEFAULT }

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
        runCatching { handle.resume() }

        val job = Job(ti, handle, saveDir)
        jobs[job.key] = job
        Thread { monitor(job) }.apply { isDaemon = true }.start()
        return job
    }

    /** 모니터 스레드: 진행률/속도 갱신 + 완료/실패 감지 */
    private fun monitor(job: Job) {
        while (true) {
            val st = runCatching { job.handle.status() }.getOrNull()
            if (st == null) {
                if (job.state == State.RUNNING) { job.state = State.FAILED; onJobFailed?.invoke(job) }
                return
            }
            job.progress = st.progress()
            job.downRate = st.downloadRate()
            job.upRate = st.uploadRate()
            job.doneBytes = st.totalDone()
            job.seeds = st.numSeeds()
            job.peers = st.numPeers()
            if (job.state == State.CANCELLED) return
            val ec = runCatching { st.errorCode() }.getOrNull()
            if (ec != null && ec.isError) {
                job.state = State.FAILED
                job.error = ec.message
                onJobFailed?.invoke(job)
                return
            }
            if (st.progress() >= 1f) {
                job.state = State.DONE
                job.progress = 1f
                onJobDone?.invoke(job)
                return
            }
            Thread.sleep(1000)
        }
    }

    @Synchronized
    fun pauseJob(key: String) {
        jobs[key]?.let { j ->
            if (j.state == State.RUNNING) {
                runCatching { j.handle.pause() }
                j.state = State.PAUSED
            }
        }
    }

    /** 다운 완료 후 시딩만 중지 — 상태는 DONE 유지 (업로드 안 함) */
    @Synchronized
    fun stopSeeding(key: String) {
        jobs[key]?.let { j ->
            if (j.state == State.DONE) runCatching { j.handle.pause() }
        }
    }

    @Synchronized
    fun resumeJob(key: String) {
        jobs[key]?.let { j ->
            if (j.state == State.PAUSED || j.state == State.CANCELLED || j.state == State.FAILED) {
                runCatching { j.handle.resume() }
                if (j.state != State.PAUSED) {
                    // 세션에서 제거된 경우 다시 등록
                    if (runCatching { j.handle.status() }.getOrNull() == null) {
                        runCatching {
                            val fs = j.info.files()
                            session?.download(j.info, j.saveDir, null, Array(fs.numFiles()) { Priority.DEFAULT }, null, torrent_flags_t())
                        }
                    }
                }
                j.state = State.RUNNING
                Thread { monitor(j) }.apply { isDaemon = true }.start()
            }
        }
    }

    /** 취소: 세션에서 내리지만 목록에는 남김 (삭제 버튼으로 항목+파일 제거) */
    @Synchronized
    fun cancelJob(key: String) {
        jobs[key]?.let { j ->
            j.state = State.CANCELLED
            runCatching { session?.remove(j.handle) }
        }
    }

    /** 목록에서 제거. 취소/실패 항목은 받던 파일도 함께 삭제 */
    @Synchronized
    fun deleteJob(key: String, deleteFiles: Boolean) {
        val j = jobs.remove(key) ?: return
        runCatching { session?.remove(j.handle) }
        if (deleteFiles) {
            runCatching {
                val fs = j.info.files()
                val base = File(j.saveDir, fs.filePath(0)).parentFile
                base?.deleteRecursively()
            }
        }
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

    /** 마그넷 추가: 메타데이터를 먼저 받아 파일 목록을 확인한 뒤 add()로 등록 (IO 스레드) */
    fun addMagnet(magnet: String, saveDir: File, maxActive: Int = Int.MAX_VALUE, timeoutSec: Int = 120): Job? {
        val ti = fetchMagnetInfo(magnet, timeoutSec, saveDir) ?: return null
        return add(ti, saveDir, maxActive)
    }

    @Synchronized
    fun stopAll() {
        jobs.clear()
        session?.stop()
        session = null
    }
}
