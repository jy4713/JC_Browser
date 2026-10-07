package com.example.streambrowser.download

import android.content.Context
import android.os.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Soul 브라우저 스타일 고속 다운로드.
 * - HLS: 세그먼트들을 동시에 여러 개씩 병렬로 받아 순서대로 합침
 * - 직접 파일(MP4/WEBM 등): Accept-Ranges 지원 시 파일을 N개로 쪼개 병렬 수신 후 합침
 * - 설정: 한 파일 분할 수(dl_split), 동시 연결 수(dl_conn)
 * - 진행률: HLS는 세그먼트 개수 기준 %, 직접 파일은 바이트 기준 %
 */
object FastVideoDownloader {

    enum class Result { DONE, CANCELED, PAUSED, FAILED }

    private val cancelFlags = ConcurrentHashMap<Long, AtomicBoolean>()
    private val pauseFlags = ConcurrentHashMap<Long, AtomicBoolean>()

    fun cancel(id: Long) {
        cancelFlags[id]?.set(true)
    }

    /** 일시 중지: 워커 중단 플래그 세팅 (부분 파일은 보존 → 이어받기 가능) */
    fun pause(id: Long) {
        pauseFlags[id]?.set(true)
        cancelFlags[id]?.set(true)
    }

    fun isPaused(id: Long) = pauseFlags[id]?.get() == true

    private fun isCanceled(id: Long) = cancelFlags[id]?.get() == true

    /** Range 요청 지원 여부 확인 */
    fun isRangeSupported(url: String, headers: String): Boolean = runCatching {
        val conn = open(url, headers)
        conn.requestMethod = "HEAD"
        val ok = conn.responseCode in 200..299
        val ar = conn.getHeaderField("Accept-Ranges")
        conn.disconnect()
        ok && ar != null && ar.lowercase() != "none"
    }.getOrDefault(false)

    fun download(ctx: Context, item: DlItem, headers: String, split: Int, conn: Int): Result {
        pauseFlags.remove(item.id)   // 재개 시 이전 중지 플래그 제거
        cancelFlags[item.id] = AtomicBoolean(false)
        val r = runCatching {
            if (item.kind == "HLS" || ".m3u8" in item.url) downloadHls(ctx, item, headers, conn)
            else downloadSplit(ctx, item, headers, split, conn)
        }.getOrElse { Result.FAILED }
        cancelFlags.remove(item.id)
        return r
    }

    // ------------------------------------------------------- HLS 병렬 다운로드

    private fun downloadHls(ctx: Context, item: DlItem, headers: String, conn: Int): Result {
        var text = fetchText(item.url, headers) ?: return Result.FAILED
        if ("EXT-X-STREAM-INF" in text) {
            // 변형 재생목록 중 마지막(보통 최고 화질) 선택
            val lines = text.lines()
            var variant: String? = null
            for (i in lines.indices) {
                if ("EXT-X-STREAM-INF" in lines[i]) {
                    for (j in i + 1 until lines.size) {
                        val t = lines[j].trim()
                        if (t.isNotEmpty() && !t.startsWith("#")) { variant = t; break }
                    }
                }
            }
            val v = variant ?: return Result.FAILED
            text = fetchText(resolve(item.url, v), headers) ?: return Result.FAILED
        }
        val segs = mutableListOf<String>()
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isNotEmpty() && !t.startsWith("#")) segs.add(resolve(item.url, t))
        }
        if (segs.isEmpty()) return Result.FAILED

        item.totalDurationMs = segs.size.toLong()   // % = 받은 세그먼트/전체
        item.status = DlStatus.RUNNING
        DownloadStore.upsert(item)

        val out = uniqueFile(ctx, item.name, item.ext)
        val partsDir = File(ctx.cacheDir, "hls_${item.id}").apply { mkdirs() }
        val doneSegs = AtomicInteger(0)
        val fail = AtomicBoolean(false)
        val bytes = AtomicLong(0)
        val start = System.currentTimeMillis()

        val pool = Executors.newFixedThreadPool(conn.coerceIn(1, 16))
        segs.forEachIndexed { idx, url ->
            pool.submit {
                if (isCanceled(item.id) || fail.get()) return@submit
                val ok = runCatching {
                    val p = File(partsDir, "$idx.part")
                    if (p.length() <= 0L) {
                        // 임시 파일에 받고 완료 시에만 .part로 이동 (중간 중단된 조각이 완료로 오인 방지)
                        val tmp = File(partsDir, "$idx.part.dl")
                        tmp.delete()
                        downloadTo(url, headers, tmp)
                        if (isCanceled(item.id)) {
                            tmp.delete()
                        } else if (tmp.length() > 0L) {
                            tmp.renameTo(p)
                        }
                    }
                    p.length() > 0L
                }.getOrDefault(false)
                if (!ok) { fail.set(true); return@submit }
                bytes.addAndGet(File(partsDir, "$idx.part").length())
                item.currentTimeMs = doneSegs.incrementAndGet().toLong()
                item.doneBytes = bytes.get()
                item.speedBps = speed(bytes.get(), start)
                DownloadStore.upsert(item)
            }
        }
        pool.shutdown()
        while (!pool.awaitTermination(300, TimeUnit.MILLISECONDS)) {
            // 취소 플래그는 워커가 주기적으로 확인
        }

        if (isCanceled(item.id)) {
            // 일시 중지면 부분 세그먼트를 보존해 이어받기 가능하게 함
            if (isPaused(item.id)) return Result.PAUSED
            partsDir.deleteRecursively(); out.delete()
            return Result.CANCELED
        }
        if (fail.get()) {
            partsDir.deleteRecursively(); out.delete()
            return Result.FAILED
        }

        // 순서대로 합치기
        out.outputStream().use { o ->
            for (i in segs.indices) {
                File(partsDir, "$i.part").inputStream().use { it.copyTo(o) }
            }
        }
        partsDir.deleteRecursively()

        // 원본이 MPEG-TS 조각이므로 ts 확장자가 정확함
        if (item.ext == "mp4") {
            val ts = File(out.parentFile, out.nameWithoutExtension + ".ts")
            out.renameTo(ts)
            item.file = ts
            // WebView <video>는 TS 컨테이너를 재생 못 하므로 스트림 복사로 mp4 재먹스 시도
            val mp4 = File(out.parentFile, out.nameWithoutExtension + ".mp4")
            val rc = runCatching {
                com.arthenica.ffmpegkit.FFmpegKit.execute(
                    "-y -i \"${ts.absolutePath}\" -c copy -bsf:a aac_adtstoasc \"${mp4.absolutePath}\""
                )
            }.getOrNull()
            if (rc != null && rc.returnCode.isValueSuccess && mp4.length() > 0) {
                ts.delete()
                item.file = mp4
            } else {
                mp4.delete()
            }
        } else {
            item.file = out
        }
        item.doneBytes = item.file?.length() ?: 0
        item.speedBps = speed(bytes.get(), start)
        item.status = DlStatus.DONE
        DownloadStore.upsert(item)
        return Result.DONE
    }

    // ------------------------------------------------------- 직접 파일 분할 다운로드

    private fun downloadSplit(ctx: Context, item: DlItem, headers: String, split: Int, conn: Int): Result {
        val size = contentLength(item.url, headers) ?: return Result.FAILED
        if (size <= 0) return Result.FAILED
        val n = split.coerceIn(2, 32)

        val out = uniqueFile(ctx, item.name, item.ext)
        val partsDir = File(ctx.cacheDir, "split_${item.id}").apply { mkdirs() }
        val bytes = AtomicLong(0)
        val fail = AtomicBoolean(false)
        val start = System.currentTimeMillis()

        item.totalDurationMs = size
        item.status = DlStatus.RUNNING
        DownloadStore.upsert(item)

        val ranges = (0 until n).map { i ->
            val s = size * i / n
            val e = if (i == n - 1) size - 1 else (size * (i + 1) / n) - 1
            Triple(s, e, i)
        }

        val pool = Executors.newFixedThreadPool(conn.coerceIn(1, 16))
        ranges.forEach { (s, e, i) ->
            pool.submit {
                if (isCanceled(item.id) || fail.get()) return@submit
                val ok = runCatching {
                    val p = File(partsDir, "$i.part")
                    val expect = e - s + 1
                    if (p.length() != expect) downloadRange(item.url, headers, s, e, p, bytes)
                    p.length() == expect
                }.getOrDefault(false)
                if (!ok) fail.set(true)
                item.currentTimeMs = bytes.get()
                item.speedBps = speed(bytes.get(), start)
                DownloadStore.upsert(item)
            }
        }
        pool.shutdown()
        while (!pool.awaitTermination(300, TimeUnit.MILLISECONDS)) { }

        if (isCanceled(item.id)) {
            // 일시 중지면 부분 조각을 보존해 이어받기 가능하게 함
            if (isPaused(item.id)) return Result.PAUSED
            partsDir.deleteRecursively(); out.delete()
            return Result.CANCELED
        }
        if (fail.get()) {
            partsDir.deleteRecursively(); out.delete()
            return Result.FAILED
        }

        out.outputStream().use { o ->
            for (i in 0 until n) {
                File(partsDir, "$i.part").inputStream().use { it.copyTo(o) }
            }
        }
        partsDir.deleteRecursively()
        item.file = out
        item.doneBytes = size
        item.speedBps = speed(bytes.get(), start)
        item.status = DlStatus.DONE
        DownloadStore.upsert(item)
        return Result.DONE
    }

    // ------------------------------------------------------- HTTP 헬퍼

    private fun speed(done: Long, startMs: Long): Long {
        val sec = (System.currentTimeMillis() - startMs).coerceAtLeast(1) / 1000.0
        return (done / sec).toLong()
    }

    private fun open(url: String, headers: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        headers.lines().filter { it.contains(":") }.forEach {
            val k = it.substringBefore(":").trim()
            val v = it.substringAfter(":").trim()
            if (k.isNotEmpty()) conn.setRequestProperty(k, v)
        }
        return conn
    }

    private fun fetchText(url: String, headers: String): String? = runCatching {
        val conn = open(url, headers)
        if (conn.responseCode != 200) { conn.disconnect(); return null }
        val t = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        conn.disconnect()
        t
    }.getOrNull()

    private fun contentLength(url: String, headers: String): Long? = runCatching {
        val conn = open(url, headers)
        conn.requestMethod = "HEAD"
        val len = if (conn.responseCode in 200..299) conn.contentLength.toLong() else -1L
        conn.disconnect()
        if (len > 0) len else null
    }.getOrNull()

    private fun downloadTo(url: String, headers: String, out: File) {
        val conn = open(url, headers)
        if (conn.responseCode != 200) { conn.disconnect(); return }
        conn.inputStream.use { inp ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = inp.read(buf)
                    if (r < 0) break
                    o.write(buf, 0, r)
                }
            }
        }
        conn.disconnect()
    }

    private fun downloadRange(url: String, headers: String, start: Long, end: Long, out: File, counter: AtomicLong) {
        val conn = open(url, headers)
        conn.setRequestProperty("Range", "bytes=$start-$end")
        if (conn.responseCode !in 200..299) { conn.disconnect(); return }
        conn.inputStream.use { inp ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = inp.read(buf)
                    if (r < 0) break
                    o.write(buf, 0, r)
                    counter.addAndGet(r.toLong())
                }
            }
        }
        conn.disconnect()
    }

    private fun resolve(base: String, rel: String): String =
        runCatching { URL(URL(base), rel).toString() }.getOrDefault(rel)

    private fun uniqueFile(ctx: Context, name: String, ext: String): File {
        val outDir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
        outDir.mkdirs()
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "video" }
        val safeExt = ext.replace(Regex("[^a-zA-Z0-9]"), "").ifEmpty { "mp4" }
        var f = File(outDir, "$safe.$safeExt")
        var i = 1
        while (f.exists()) {
            f = File(outDir, "${safe}_$i.$safeExt")
            i++
        }
        return f
    }
}
