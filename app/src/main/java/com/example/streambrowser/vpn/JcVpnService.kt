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
 * ics-openvpn의 openvpn 2.x 안드로이드 바이너리(assets/vpn/openvpn.<abi>)를 실행하고,
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

        private fun setState(s: String, profile: String? = null, err: String? = null) {
            state = s
            profile?.let { stateProfile = it }
            err?.let { lastError = it }
            runCatching { onStateChange?.invoke() }
        }
    }

    private var process: Process? = null
    private var mgmtSocket: LocalSocket? = null
    private var serverSocket: LocalServerSocket? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private var stopRequested = false

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
            setState("ERROR", err = "프로파일을 찾을 수 없습니다")
            stopSelf(); return START_NOT_STICKY
        }
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: profile.username
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: profile.password

        stopRequested = false
        setState("CONNECTING", profile.name)
        createChannel()
        startForeground(NOTIF_ID, buildNotification(profile.name, "연결 중…"))

        thread(name = "jc-vpn") { runVpn(profile, username, password) }
        return START_NOT_STICKY
    }

    private fun runVpn(profile: VpnProfiles.Profile, username: String, password: String) {
        val bin = extractBinary() ?: run {
            setState("ERROR", profile.name, "OpenVPN 바이너리를 준비하지 못했습니다 (지원하지 않는 CPU)")
            stopSelf(); return
        }
        val sockPath = File(cacheDir, "jcmgmt.sock").absolutePath
        File(sockPath).delete()

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
            setState("ERROR", profile.name, "management 소켓 생성 실패")
            stopSelf(); return
        }
        serverSocket = LocalServerSocket(acceptSock.fileDescriptor)

        val argv = listOf(
            bin.absolutePath,
            "--config", profile.file.absolutePath,
            "--management", sockPath, "unix",
            "--management-query-passwords",
            "--management-hold",
            "--route-noexec",
            "--ifconfig-noexec",
            "--auth-retry", "interact",
            "--verb", "3",
            "--mute-replay-warnings"
        )

        val pb = ProcessBuilder(argv)
        pb.environment()["TMPDIR"] = cacheDir.absolutePath
        pb.redirectErrorStream(true)

        try {
            process = pb.start()
        } catch (e: Exception) {
            setState("ERROR", profile.name, "OpenVPN 실행 실패: ${e.message}")
            stopSelf(); return
        }

        // openvpn 프로세스 stdout → 로그 + 상태 감지
        val proc = process!!
        thread(name = "jc-vpn-log") {
            val br = BufferedReader(InputStreamReader(proc.inputStream))
            try {
                while (true) {
                    val line = br.readLine() ?: break
                    android.util.Log.i("jc-openvpn", line)
                    if (line.contains("Cannot open tun", true) || line.contains("Exiting", true)) {
                        // 종료 징후 — management 쪽에서도 처리하므로 여기선 로그만
                    }
                }
            } catch (_: Exception) {}
        }

        // management 연결 수락 + 처리
        try {
            val s = serverSocket!!.accept()
            mgmtSocket = s
            manageLoop(s, profile, username, password)
        } catch (e: Exception) {
            if (!stopRequested) android.util.Log.w("jc-vpn", "mgmt end: ${e.message}")
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
                        when (st) {
                            "CONNECTED" -> {
                                setState("CONNECTED", profile.name)
                                updateNotif(profile.name, "연결됨")
                            }
                            "WAIT", "RECONNECTING" -> { setState("WAIT", profile.name); updateNotif(profile.name, "서버 연결 대기…") }
                            "AUTH", "GET_CONFIG", "RESOLVE" -> { setState("AUTH", profile.name); updateNotif(profile.name, "인증 중…") }
                            "ASSIGN_IP" -> { setState("ASSIGN_IP", profile.name); updateNotif(profile.name, "IP 할당 중…") }
                            "EXITING" -> { setState("DISCONNECTED", profile.name) }
                        }
                    }

                    // --- hold: 준비 완료 후 해제 ---
                    line.startsWith(">HOLD:") -> {
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
                            setState("ERROR", profile.name, "인증 실패 — 아이디/비밀번호를 확인하세요")
                            updateNotif(profile.name, "인증 실패")
                        } else {
                            val p1 = arg.indexOf('\'')
                            val p2 = arg.indexOf('\'', p1 + 1)
                            val needed = if (p1 >= 0 && p2 > p1) arg.substring(p1 + 1, p2) else "Auth"
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
                                val pfd = openTun(profile.name, cfg)
                                if (pfd != null && sendFd(s, pfd)) {
                                    tunPfd = pfd
                                } else {
                                    runCatching { pfd?.close() }
                                    setState("ERROR", profile.name, "VPN 인터페이스 생성 실패 (시스템 VPN 권한 확인)")
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

                    line.startsWith(">BYTECOUNT:") -> { /* 트래픽 통계 — UI 미사용 */ }

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

    // ---------------- 바이너리 추출 ----------------

    private fun extractBinary(): File? {
        val abi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.firstOrNull { a ->
                assets.list("vpn")?.any { it == "openvpn.$a" } == true
            }
        } else null ?: return null
        val target = File(filesDir, "vpn/openvpn")
        if (target.exists() && target.length() > 1_000_000) return target
        target.parentFile?.mkdirs()
        runCatching {
            assets.open("vpn/openvpn.$abi").use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
            target.setExecutable(true, true)
        }.onFailure { return null }
        return if (target.canExecute()) target else null
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
            .addAction(0, "연결 해제", disconnect)
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
