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
        /** VPN 전환 등으로 세션이 재시작되면 핸들이 바뀔 수 있어 var */
        var handle: TorrentHandle,
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
                // 세션에서 제거된 경우 다시 등록하고 새 핸들로 갱신
                if (j.state != State.PAUSED || runCatching { j.handle.status() }.getOrNull() == null) {
                    runCatching {
                        val fs = j.info.files()
                        session?.download(j.info, j.saveDir, null, Array(fs.numFiles()) { Priority.DEFAULT }, null, torrent_flags_t())
                        for (i in 0 until 50) {
                            val h = session?.find(j.info.infoHash())
                            if (h != null) { j.handle = h; break }
                            Thread.sleep(100)
                        }
                    }
                }
                runCatching { j.handle.resume() }
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
     * 마그넷에서 infohash 추출 (소문자 hex, base32 도 디코딩) — 중복 판정/핸들 탐색용
     */
    fun magnetHash(magnet: String): String? {
        val m = Regex("(?i)xt=urn:btih:([a-zA-Z0-9]+)").find(magnet) ?: return null
        val h = m.groupValues[1]
        if (h.length == 40) return h.lowercase()
        return if (h.length == 32) base32ToHex(h) else null
    }

    private fun base32ToHex(s: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        return runCatching {
            val out = StringBuilder()
            var buffer = 0
            var bitsLeft = 0
            for (c in s.uppercase()) {
                val v = alphabet.indexOf(c)
                if (v < 0) return null
                buffer = (buffer shl 5) or v
                bitsLeft += 5
                if (bitsLeft >= 8) {
                    out.append(((buffer ushr (bitsLeft - 8)) and 0xFF).toString(16).padStart(2, '0'))
                    bitsLeft -= 8
                }
            }
            out.toString()
        }.getOrNull()
    }

    /** 트래커가 없는 마그넷에 공개 트래커를 붙여 메타데이터 수신 성공률을 높인다 */
    fun withDefaultTrackers(magnet: String): String {
        if (magnet.contains("tr=")) return magnet
        val trackers = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce"
        )
        return magnet + trackers.joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, "UTF-8") }
    }

    /**
     * 마그넷 추가: 세션에 마그넷을 직접 등록(일반 .torrent 와 동일한 경로)해
     * 메타데이터를 받은 뒤 Job 을 생성한다. 블로킹 — IO 스레드에서.
     * fetchMagnet() 는 일부 기기/환경에서 네이티브 크래시를 일으켜 사용하지 않음.
     * @return null 이면 메타데이터 수신 실패(시드 없음/시간 초과)
     * @throws IllegalStateException("max_active") 동시 다운로드 상한 초과
     */
    @Synchronized
    fun addMagnet(magnet: String, saveDir: File, maxActive: Int = Int.MAX_VALUE, timeoutSec: Int = 180): Job? {
        val hash = magnetHash(magnet)
        if (hash != null) {
            jobs[hash]?.let { existing ->
                if (existing.state == State.CANCELLED || existing.state == State.FAILED) resumeJob(existing.key)
                return existing
            }
        }
        val running = jobs.values.count { it.state == State.RUNNING }
        if (running >= maxActive) throw IllegalStateException("max_active")
        val s = ensureSession()
        saveDir.mkdirs()
        runCatching { s.download(magnet, saveDir, torrent_flags_t()) }.onFailure { return null }
        // 등록된 핸들 탐색 (infohash 매칭)
        var handle: TorrentHandle? = null
        for (i in 0 until 100) {
            handle = runCatching {
                val v = s.swig().get_torrents()
                (0 until v.size)
                    .map { TorrentHandle(v.get(it)) }
                    .firstOrNull { runCatching { it.infoHash().toHex().lowercase() }.getOrNull() == hash }
            }.getOrNull()
            if (handle != null) break
            Thread.sleep(100)
        }
        val h = handle ?: return null
        // 메타데이터 수신 대기
        for (i in 0 until timeoutSec) {
            val st = runCatching { h.status() }.getOrNull() ?: return null
            if (runCatching { st.hasMetadata() }.getOrDefault(false)) break
            if (i == timeoutSec - 1) {
                runCatching { s.remove(h) }
                return null
            }
            Thread.sleep(1000)
        }
        val ti = runCatching { h.torrentFile() }.getOrNull() ?: return null
        runCatching { h.resume() }
        val job = Job(ti, h, saveDir)
        jobs[job.key] = job
        Thread { monitor(job) }.apply { isDaemon = true }.start()
        return job
    }

    /**
     * 세션 재시작 (VPN 연결/해제 전환 시) — 재시작 후 기존 작업을 세션에 다시 등록.
     * tun 기반 VPN 은 소켓이 연결 이후 생성돼야 경유되므로, VPN 상태가 바뀌면
     * 토렌트 세션을 재시작해 이후 소켓들이 새 경로(또는 일반 경로)를 쓰게 함
     */
    @Synchronized
    fun restartSession() {
        val hasSession = session != null
        val reattach = jobs.values.filter {
            it.state == State.RUNNING || it.state == State.PAUSED || it.state == State.DONE
        }
        if (!hasSession && reattach.isEmpty()) return
        runCatching { session?.stop() }
        session = null
        if (reattach.isEmpty()) return
        val s = ensureSession()
        reattach.forEach { j ->
            runCatching {
                val fs = j.info.files()
                s.download(j.info, j.saveDir, null, Array(fs.numFiles()) { Priority.DEFAULT }, null, torrent_flags_t())
                for (i in 0 until 100) {
                    val h = s.find(j.info.infoHash())
                    if (h != null) { j.handle = h; break }
                    Thread.sleep(100)
                }
                runCatching { j.handle.resume() }
            }
        }
    }

    @Synchronized
    fun stopAll() {
        jobs.clear()
        session?.stop()
        session = null
    }
}
