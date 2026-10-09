package com.example.streambrowser.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.core.app.NotificationCompat
import com.example.streambrowser.R
import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.LinkedList
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 브라우저 전용 OpenVPN 서비스.
 *
 * ics-openvpn의 openvpn 2.x 안드로이드 바이너리(jniLibs/libjcopenvpn.so, GPL v2 — 출처는 assets/vpn/NOTICE.md)를 실행하고,
 * management unix 소켓으로 연동한다:
 *  - >PASSWORD  → Auth 사용자/비밀번호 응답 (auth-user-pass 대응)
 *  - >NEED-OK OPENTUN → VpnService.Builder로 만든 tun fd 를 소켓 ancillary 데이터로 전달
 *  - >NEED-OK PROTECTFD → openvpn 소켓을 VpnService.protect() 로 우회 등록
 *  - IFCONFIG/DNS/ROUTE 수집 → OPENTUN 때 Builder에 반영
 *
 * "브라우저에만 영향": Builder.addAllowedApplication(자기 패키지) 로 본 앱 트래픽만 VPN 경유.
 */
class JcVpnService : VpnService() {

    companion object {
        const val CHANNEL_ID = "vpn"
        const val NOTIF_ID = 3001
        const val ACTION_CONNECT = "jc.vpn.CONNECT"
        const val ACTION_DISCONNECT = "jc.vpn.DISCONNECT"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"

        /** UI 갱신용 상태 (간단 싱글턴 — 앱 낶에서만 사용) */
        @Volatile var state: String = "DISCONNECTED"   // CONNECTING / WAIT / AUTH / ASSIGN_IP / CONNECTED / DISCONNECTED / ERROR
        @Volatile var stateProfile: String = ""
        @Volatile var lastError: String = ""
        @Volatile var onStateChange: (() -> Unit)? = null

        /** 속도/누적 통계 (bytecount 5 기반, bytes per second) */
        @Volatile var rxRate: Long = 0
        @Volatile var txRate: Long = 0
        @Volatile var rxTotal: Long = 0
        @Volatile var txTotal: Long = 0
        /** CONNECTED 된 시각 (epoch ms), 연결 아니면 0 */
        @Volatile var connectedSince: Long = 0

        /** UI 로그 콘솔용 최근 로그 (링 버퍼, 최대 300줄) */
        val logLines = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var onLog: ((String) -> Unit)? = null
        private val logFmt = java.text.SimpleDateFormat("HH:mm:ss", Locale.US)

        fun logLine(tag: String, msg: String) {
            val line = "${logFmt.format(java.util.Date())} [$tag] $msg"
            logLines.add(line)
            while (logLines.size > 300) logLines.removeAt(0)
            runCatching { onLog?.invoke(line) }
        }

        fun clearLog() = logLines.clear()

        private fun setState(s: String, profile: String? = null, err: String? = null) {
            state = s
            profile?.let { stateProfile = it }
            err?.let { lastError = it }
            logLine("jc", "state: " + s + (profile?.let { " ($it)" } ?: "") + (err?.let { " — $it" } ?: ""))
            if (s == "CONNECTED") {
                connectedSince = System.currentTimeMillis()
                rxRate = 0; txRate = 0
            }
            if (s == "DISCONNECTED" || s == "ERROR") {
                connectedSince = 0
                rxRate = 0; txRate = 0; rxTotal = 0; txTotal = 0
            }
            runCatching { onStateChange?.invoke() }
        }
    }

    private var process: Process? = null
    private var mgmtSocket: LocalSocket? = null
    private var serverSocket: LocalServerSocket? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private var stopRequested = false

    // bytecount 속도 계산용 (누적값은 companion 에 게시)
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastByteAt = 0L

    // OPENTUN 직전까지 수집된 인터페이스 구성
    private class TunCfg {
        var localIp: String? = null
        var localPrefix = 32
        var localV6: String? = null
        var v6Prefix = 128
        var mtu = 1500
        val dns = mutableListOf<String>()
        val routes = mutableListOf<Pair<String, Int>>()   // network, prefix
        val routesV6 = mutableListOf<Pair<String, Int>>()
    }

    override fun onBind(intent: Intent?) = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> { stopVpn(); return START_NOT_STICKY }
            ACTION_CONNECT -> { /* 아래 진행 */ }
            else -> return START_NOT_STICKY
        }
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: return START_NOT_STICKY
        val profile = VpnProfiles.get(this, profileId) ?: run {
            setState("ERROR", err = getString(R.string.vpn_err_no_profile))
            stopSelf(); return START_NOT_STICKY
        }
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: profile.username
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: profile.password

        stopRequested = false
        setState("CONNECTING", profile.name)
        logLine("jc", getString(R.string.vpn_log_start, profile.name, profile.file.name))
        createChannel()
        startForeground(NOTIF_ID, buildNotification(profile.name, getString(R.string.vpn_notif_connecting)))

        thread(name = "jc-vpn") { runVpn(profile, username, password) }
        return START_NOT_STICKY
    }

    private fun runVpn(profile: VpnProfiles.Profile, username: String, password: String) {
        val bin = extractBinary() ?: run {
            setState("ERROR", profile.name, getString(R.string.vpn_err_binary))
            stopSelf(); return
        }
        val sockPath = File(cacheDir, "jcmgmt.sock").absolutePath
        File(sockPath).delete()
        // openvpn 자체 로그 파일 — 프로세스가 강제로 죽으면 stdio 버퍼가 날아가
        // 출력이 하나도 안 남는데, --log 는 openvpn 로깅 시스템이 직접 쓰므로 남음
        val ovpnLog = File(cacheDir, "jcovpn.log")
        ovpnLog.delete()

        // management 수신 소켓 먼저 준비
        val acceptSock = LocalSocket()
        var bound = false
        var tries = 10
        while (tries-- > 0 && !bound) {
            try {
                acceptSock.bind(LocalSocketAddress(sockPath, LocalSocketAddress.Namespace.FILESYSTEM))
                bound = true
            } catch (e: Exception) {
                Thread.sleep(300)
            }
        }
        if (!bound) {
            setState("ERROR", profile.name, getString(R.string.vpn_err_mgmt_socket))
            stopSelf(); return
        }
        serverSocket = LocalServerSocket(acceptSock.fileDescriptor)
        // ★ 중요: LocalServerSocket(FileDescriptor) 생성자는 listen()을 하지 않음.
        // listen 없이 두면 openvpn 의 connect() 가 ECONNREFUSED 로 실패해 프로세스가 죽고
        // UI 는 "연결 중…"에 무한히 멈춰 있게 됨 (openvpn 은 로그에만 에러를 남김)
        runCatching { Os.listen(acceptSock.fileDescriptor, 4) }
        logLine("jc", getString(R.string.vpn_log_mgmt_ready, sockPath))

        val argv = listOf(
            bin.absolutePath,
            "--config", profile.file.absolutePath,
            "--management", sockPath, "unix",
            "--management-query-passwords",
            "--management-hold",
            "--route-noexec",
            "--ifconfig-noexec",
            "--auth-retry", "interact",
            "--log", ovpnLog.absolutePath,
            "--verb", "3",
            "--mute-replay-warnings"
        )

        val pb = ProcessBuilder(argv)
        pb.environment()["TMPDIR"] = cacheDir.absolutePath
        // minivpn이 libopenvpn.so를 찾을 수 있게 라이브러리 디렉터리 지정
        pb.environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
        pb.redirectErrorStream(true)

        try {
            process = pb.start()
        } catch (e: Exception) {
            setState("ERROR", profile.name, getString(R.string.vpn_err_exec, e.message ?: "?"))
            stopSelf(); return
        }

        // openvpn 프로세스 stdout → 로그 + 상태 감지
        val proc = process!!
        thread(name = "jc-vpn-log") {
            var lastFatal: String? = null
            val br = BufferedReader(InputStreamReader(proc.inputStream))
            try {
                while (true) {
                    val line = br.readLine() ?: break
                    android.util.Log.i("jc-openvpn", line)
                    logLine("ovpn", line)
                    if (line.startsWith("Options error:") ||
                        line.contains("Cannot open", true) ||
                        line.contains("Cannot ioctl", true) ||
                        line.contains("AUTH_FAILED") ||
                        line.contains("fatal", true)
                    ) {
                        lastFatal = line.trim()
                    }
                }
            } catch (_: Exception) {}
            // stdout 이 닫힘 = 프로세스 종료. EOF 직후엔 아직 종료 처리가 안 끝난 경우가 있어 잠깐 대기
            runCatching { proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }
            val exitCode = runCatching { proc.exitValue() }.getOrNull()
            logLine("jc", getString(R.string.vpn_log_exit, exitCode?.toString() ?: "?"))
            dumpOpenvpnLog(ovpnLog)
            if (!stopRequested && state != "ERROR" && state != "DISCONNECTED") {
                setState("ERROR", profile.name,
                    lastFatal ?: getString(R.string.vpn_err_died, exitCode?.toString() ?: "?"))
            }
        }

        // management 연결 수락 + 처리
        try {
            val s = serverSocket!!.accept()
            mgmtSocket = s
            logLine("mgmt", getString(R.string.vpn_log_mgmt_connected))
            manageLoop(s, profile, username, password)
        } catch (e: Exception) {
            if (!stopRequested) {
                android.util.Log.w("jc-vpn", "mgmt end: ${e.message}")
                logLine("jc", getString(R.string.vpn_log_mgmt_end, e.message ?: "?"))
            }
        }

        // 종료 정리
        runCatching { proc.destroy() }
        runCatching { tunPfd?.close() }
        tunPfd = null
        if (!stopRequested) setState("DISCONNECTED", profile.name)
        stopForeground(true)
        stopSelf()
    }

    /** management 명령 전송 */
    private fun mgmtCmd(s: LocalSocket, cmd: String): Boolean = runCatching {
        s.outputStream.write(cmd.toByteArray())
        s.outputStream.flush()
        true
    }.getOrDefault(false)

    /** openvpn 자체 로그 파일(--log)의 마지막 줄들을 콘솔에 덤프 — stdio 출력이 없을 때 원인 확인용 */
    private fun dumpOpenvpnLog(f: File) {
        if (!f.exists()) {
            logLine("jc", getString(R.string.vpn_log_missing))
            return
        }
        val lines = runCatching { f.readLines() }.getOrNull() ?: return
        if (lines.isEmpty()) {
            logLine("jc", getString(R.string.vpn_log_empty))
            return
        }
        logLine("jc", getString(R.string.vpn_log_tail, 15, lines.size))
        lines.takeLast(15).forEach { logLine("ovpn-log", it) }
    }

    /** >BYTECOUNT 수신 시 속도 계산 → companion 에 게시 (UI 1초 폴링) */
    private fun updateBytecount(rx: Long, tx: Long) {
        val now = System.currentTimeMillis()
        if (lastByteAt > 0) {
            val dt = (now - lastByteAt).coerceAtLeast(1)
            rxRate = ((rx - lastRx).coerceAtLeast(0) * 1000L) / dt
            txRate = ((tx - lastTx).coerceAtLeast(0) * 1000L) / dt
        }
        lastRx = rx; lastTx = tx; lastByteAt = now
        rxTotal = rx; txTotal = tx
    }

    private fun manageLoop(s: LocalSocket, profile: VpnProfiles.Profile, username: String, password: String) {
        val cfg = TunCfg()
        val fdQueue = LinkedList<FileDescriptor>()
        val input = s.inputStream
        val buf = ByteArray(4096)
        var pending = ""
        var holdReleased = false

        mgmtCmd(s, "version 3\n")

        while (!stopRequested) {
            val n = input.read(buf)
            if (n < 0) break

            // ancillary fds (PROTECTFD / 기타 fd 동반 메시지)
            runCatching {
                s.ancillaryFileDescriptors?.let { fds -> fdQueue.addAll(fds) }
            }

            pending += String(buf, 0, n, Charsets.UTF_8)
            while (pending.contains("\n")) {
                val idx = pending.indexOf("\n")
                var line = pending.substring(0, idx).trimEnd('\r')
                pending = pending.substring(idx + 1)
                if (line.isEmpty()) continue

                when {
                    // --- 상태 ---
                    line.startsWith(">STATE:") -> {
                        val parts = line.removePrefix(">STATE:").split(",")
                        val st = parts.getOrNull(1) ?: ""
                        logLine("mgmt", "STATE $st")
                        when (st) {
                            "CONNECTED" -> {
                                setState("CONNECTED", profile.name)
                                updateNotif(profile.name, getString(R.string.vpn_notif_connected))
                            }
                            "WAIT", "RECONNECTING" -> { setState("WAIT", profile.name); updateNotif(profile.name, getString(R.string.vpn_notif_wait)) }
                            "AUTH", "GET_CONFIG", "RESOLVE" -> { setState("AUTH", profile.name); updateNotif(profile.name, getString(R.string.vpn_notif_auth)) }
                            "ASSIGN_IP" -> { setState("ASSIGN_IP", profile.name); updateNotif(profile.name, getString(R.string.vpn_notif_assign_ip)) }
                            "EXITING" -> { setState("DISCONNECTED", profile.name) }
                        }
                    }

                    // --- hold: 준비 완료 후 해제 ---
                    line.startsWith(">HOLD:") -> {
                        logLine("mgmt", getString(R.string.vpn_log_hold))
                        if (!holdReleased) {
                            holdReleased = true
                            mgmtCmd(s, "hold release\n")
                            mgmtCmd(s, "state on\n")
                            mgmtCmd(s, "bytecount 5\n")
                        }
                    }

                    // --- 비밀번호 요구 (auth-user-pass) ---
                    line.startsWith(">PASSWORD:") -> {
                        val arg = line.removePrefix(">PASSWORD:")
                        if (arg.startsWith("Verification Failed")) {
                            setState("ERROR", profile.name, getString(R.string.vpn_err_auth))
                            updateNotif(profile.name, getString(R.string.vpn_err_auth))
                        } else {
                            val p1 = arg.indexOf('\'')
                            val p2 = arg.indexOf('\'', p1 + 1)
                            val needed = if (p1 >= 0 && p2 > p1) arg.substring(p1 + 1, p2) else "Auth"
                            logLine("mgmt", getString(R.string.vpn_log_auth_required, needed))
                            if (username.isNotEmpty() || password.isNotEmpty()) {
                                if (username.isNotEmpty())
                                    mgmtCmd(s, "username '$needed' ${escape(username)}\n")
                                mgmtCmd(s, "password '$needed' ${escape(password)}\n")
                            } else {
                                // 인증정보 없음 — 빈 값으로 시도(인증 불필요 서버) 또는 실패로 이어짐
                                mgmtCmd(s, "username '$needed' \"\"\n")
                                mgmtCmd(s, "password '$needed' \"\"\n")
                            }
                        }
                    }

                    // --- need-ok 계열 (IFCONFIG/DNS/ROUTE/OPENTUN/PROTECTFD) ---
                    line.startsWith(">NEED-OK:") || line.startsWith(">NEED-STR:") -> {
                        val arg = line.substring(line.indexOf(':') + 1)
                        val p1 = arg.indexOf('\'')
                        val p2 = arg.indexOf('\'', p1 + 1)
                        val needed = if (p1 >= 0 && p2 > p1) arg.substring(p1 + 1, p2) else ""
                        val extra = arg.substringAfter(":", "")

                        when (needed) {
                            "DNSSERVER", "DNS6SERVER" -> {
                                cfg.dns += extra
                                mgmtCmd(s, "needok '$needed' ok\n")
                            }
                            "DNSDOMAIN" -> mgmtCmd(s, "needok '$needed' ok\n")
                            "ROUTE" -> {
                                val rp = extra.trim().split(Regex("\\s+"))
                                if (rp.size >= 2) {
                                    val prefix = netmaskToPrefix(rp[1])
                                    cfg.routes += rp[0] to prefix
                                }
                                mgmtCmd(s, "needok '$needed' ok\n")
                            }
                            "ROUTE6" -> {
                                val rp = extra.trim().split(Regex("\\s+"))
                                // 형식: <addr>/<prefix> <gateway> ...
                                val ap = rp.getOrNull(0)?.split("/")
                                if (ap != null) cfg.routesV6 += (ap[0] to (ap.getOrNull(1)?.toIntOrNull() ?: 128))
                                mgmtCmd(s, "needok '$needed' ok\n")
                            }
                            "IFCONFIG" -> {
                                // IFCONFIG:<local> <remote/netmask> <mtu> <topology…>
                                val ip = extra.trim().split(Regex("\\s+"))
                                if (ip.isNotEmpty()) cfg.localIp = ip[0]
                                if (ip.size > 1) cfg.localPrefix = netmaskToPrefix(ip[1])
                                if (ip.size > 2) ip[2].toIntOrNull()?.let { cfg.mtu = it }
                                logLine("mgmt", getString(R.string.vpn_log_ifconfig, ip.getOrNull(0) ?: "?", cfg.mtu))
                                mgmtCmd(s, "needok '$needed' ok\n")
                            }
                            "IFCONFIG6" -> {
                                val ip = extra.trim().split(Regex("\\s+"))
                                val ap = ip.getOrNull(0)?.split("/")
                                if (ap != null) {
                                    cfg.localV6 = ap[0]
                                    cfg.v6Prefix = ap.getOrNull(1)?.toIntOrNull() ?: 128
                                }
                                if (ip.size > 1) ip[1].toIntOrNull()?.let { cfg.mtu = it }
                                mgmtCmd(s, "needok '$needed' ok\n")
                            }
                            "OPENTUN" -> {
                                logLine("mgmt", getString(R.string.vpn_log_opentun_try))
                                val pfd = openTun(profile.name, cfg)
                                if (pfd != null && sendFd(s, pfd)) {
                                    logLine("mgmt", getString(R.string.vpn_log_opentun_ok))
                                    tunPfd = pfd
                                } else {
                                    runCatching { pfd?.close() }
                                    setState("ERROR", profile.name, getString(R.string.vpn_err_tun))
                                    mgmtCmd(s, "needok 'OPENTUN' cancel\n")
                                }
                            }
                            "PROTECTFD" -> {
                                val fd = fdQueue.poll()
                                if (fd != null) protectFd(fd)
                                mgmtCmd(s, "needok '$needed' ok\n")
                            }
                            else -> {
                                // 알 수 없는 요청은 통과시켜 연결 자체는 진행
                                mgmtCmd(s, "needok '$needed' ok\n")
                            }
                        }
                    }

                    line.startsWith("PROTECTFD:") -> {
                        val fd = fdQueue.poll()
                        if (fd != null) protectFd(fd)
                    }

                    line.startsWith(">BYTECOUNT:") -> {
                        val arg = line.removePrefix(">BYTECOUNT:")
                        val parts = arg.split(",")
                        val rx = parts.getOrNull(0)?.trim()?.toLongOrNull()
                        val tx = parts.getOrNull(1)?.trim()?.toLongOrNull()
                        if (rx != null && tx != null) updateBytecount(rx, tx)
                    }

                    line.startsWith("SUCCESS:") -> {}
                    line.startsWith(">INFO:") -> {}
                }
            }
        }
    }

    /** ancillary 데이터로 fd 전송 (openvpn 쪽에서 OPENTUN fd 로 수신) */
    private fun sendFd(s: LocalSocket, pfd: ParcelFileDescriptor): Boolean = runCatching {
        val setInt: Method = FileDescriptor::class.java.getDeclaredMethod("setInt\$", Int::class.java)
        val fdToSend = FileDescriptor()
        setInt.invoke(fdToSend, pfd.fd)
        s.setFileDescriptorsForSend(arrayOf(fdToSend))
        // 빈 명령이 아닌 실제 명령과 함께 별도 전송 — needok 응답 전에 fd 가 먼저 도착해야 함
        s.outputStream.write("needok 'OPENTUN' ok\n".toByteArray())
        s.outputStream.flush()
        s.setFileDescriptorsForSend(null)
        true
    }.getOrDefault(false)

    private fun protectFd(fd: FileDescriptor) {
        runCatching {
            val getInt: Method = FileDescriptor::class.java.getDeclaredMethod("getInt\$")
            val fdint = getInt.invoke(fd) as Int
            if (!protect(fdint)) android.util.Log.w("jc-vpn", "protect($fdint) failed")
            Os.close(fd)
        }
    }

    /** VpnService.Builder 로 tun 생성 — 본 앱(브라우저) 트래픽만 VPN 경유 */
    private fun openTun(profileName: String, cfg: TunCfg): ParcelFileDescriptor? = runCatching {
        if (cfg.localIp == null && cfg.localV6 == null) return null
        val b = Builder()
        cfg.localIp?.let { b.addAddress(it, cfg.localPrefix) }
        cfg.localV6?.let { b.addAddress(it, cfg.v6Prefix) }
        cfg.dns.forEach { runCatching { b.addDnsServer(it) } }
        // 라우트: 받은 것 적용, 없으면 전체 터널링
        if (cfg.routes.isEmpty() && cfg.routesV6.isEmpty()) {
            b.addRoute("0.0.0.0", 0)
        } else {
            cfg.routes.forEach { (net, prefix) -> runCatching { b.addRoute(net, prefix) } }
            cfg.routesV6.forEach { (net, prefix) -> runCatching { b.addRoute(net, prefix) } }
        }
        b.setMtu(cfg.mtu.coerceIn(576, 65535))
        b.setSession(profileName)
        // ★ 핵심: 이 앱(브라우저) 트래픽만 VPN 을 타게 제한
        b.addAllowedApplication(packageName)
        b.establish()
    }.getOrNull()

    private fun netmaskToPrefix(mask: String): Int {
        if (mask.contains(".")) {
            val parts = mask.split(".").mapNotNull { it.toIntOrNull() }
            if (parts.size == 4) {
                var bits = 0
                for (p in parts) bits += Integer.bitCount(p)
                return bits
            }
            return 32
        }
        return mask.toIntOrNull() ?: 32
    }

    /** openvpn management escaping (ics-openvpn 과 동일 규칙) */
    private fun escape(v: String): String {
        val e = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return if (e == v && !e.contains(" ") && !e.contains("#") && !e.contains(";") && e.isNotEmpty() && !e.contains("'")) e
        else "\"$e\""
    }

    // ---------------- 바이너리 ----------------

    /**
     * openvpn 실행 파일 확인 — ics-openvpn 0.7.68 구조를 따름:
     * libopenvpn.so는 공유 라이브러리(SONAME 있고 PT_INTERP 없음)라 직접 exec하면
     * 시작 즉시 SIGILL(exit 132)로 죽음. 반드시 minivpn 런처(libjcminivpn.so)가
     * LD_LIBRARY_PATH로 libopenvpn.so를 로드해 실행해야 함.
     * 안드로이드 10+(targetSdk 29~)부터 앱 홈 디렉터리 exec 금지이므로 패키지 매니저가
     * 실행 비트와 함께 추출해 주는 nativeLibraryDir 방식 사용.
     */
    private fun extractBinary(): File? {
        val libDir = applicationInfo.nativeLibraryDir
        val launcher = File(libDir, "libjcminivpn.so")
        val lib = File(libDir, "libopenvpn.so")
        return if (launcher.exists() && launcher.canExecute() && lib.exists()) {
            launcher
        } else null
    }

    // ---------------- 알림 ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.example.streambrowser.ui.VpnActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val disconnect = PendingIntent.getService(
            this, 1,
            Intent(this, JcVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(0, getString(R.string.vpn_notif_disconnect), disconnect)
            .build()
    }

    private fun updateNotif(title: String, text: String) {
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(title, text))
        }
    }

    // ---------------- 정지 ----------------

    private fun stopVpn() {
        stopRequested = true
        runCatching { mgmtSocket?.close() }
        runCatching { process?.destroy() }
        runCatching { tunPfd?.close() }
        tunPfd = null
        setState("DISCONNECTED")
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopRequested = true
        runCatching { mgmtSocket?.close() }
        runCatching { serverSocket?.close() }
        runCatching { process?.destroy() }
        runCatching { tunPfd?.close() }
        tunPfd = null
        super.onDestroy()
    }
}
