package com.example.streambrowser.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.streambrowser.R
import com.example.streambrowser.util.JcToast
import com.example.streambrowser.vpn.JcVpnService
import com.example.streambrowser.vpn.VpnProfiles

/**
 * VPN 화면 (일반적인 VPN 앱 스타일)
 *
 * [메인 화면]
 *  - 상태 카드: 현재 연결된 프로파일 이름 + 상태 + 실시간 속도(▼다운 ▲업) + 연결 시간 + 연결 해제 버튼
 *  - 연결 없음: 자물쇠 아이콘 + 꺼짐 안내
 *  - 아래 "프로파일 관리" 버튼 → 관리 화면
 *
 * [프로파일 관리 화면]
 *  - 추가(직접 입력 / 파일 1개) / 여러 개 가져오기
 *  - 목록: 각 항목 오른쪽에 연결(둥근 버튼) / 이름 변경 / 삭제
 *  - 연결 중이면 항목 아래 진행 상태 표시, 연결되면 "연결됨", 버튼 누륾면 해제
 *  - auth-user-pass 프로파일은 연결 전 크리덴셜 입력 (저장 선택)
 */
class VpnActivity : Activity() {

    companion object {
        private const val REQ_PICK_ONE = 11
        private const val REQ_PICK_MULTI = 12
        private const val REQ_VPN_PREPARE = 13
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingConnectId: String? = null
    private var showProfiles = false

    // 메인 화면 위젯
    private lateinit var statusView: LinearLayout
    private lateinit var cardBox: LinearLayout
    private lateinit var txtTitleMain: TextView

    // 관리 화면 위젯
    private lateinit var profilesView: LinearLayout
    private lateinit var listBox: LinearLayout

    // 1초 폧링: 연결 중일 때 속도/시간 갱신
    private val ticker = object : Runnable {
        override fun run() {
            if (!showProfiles) renderStatusCard()
            handler.postDelayed(this, 1000)
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.example.streambrowser.util.LocaleHelper.wrap(com.example.streambrowser.util.ThemeHelper.wrap(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.streambrowser.util.ThemeHelper.apply(this)
        VpnProfiles.init(this)

        val root = FrameLayout(this)

        statusView = buildStatusView()
        profilesView = buildProfilesView()
        root.addView(statusView)
        root.addView(profilesView)
        setContentView(root)
        showScreen(profiles = false)

        JcVpnService.onStateChange = { runOnUiThread { refreshAll() } }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        JcVpnService.onStateChange = null
        super.onDestroy()
    }

    // ---------------- 화면 전환 ----------------

    private fun showScreen(profiles: Boolean) {
        showProfiles = profiles
        statusView.visibility = if (profiles) View.GONE else View.VISIBLE
        profilesView.visibility = if (profiles) View.VISIBLE else View.GONE
        refreshAll()
    }

    private fun refreshAll() {
        if (showProfiles) refreshProfiles() else renderStatusCard()
    }

    // ---------------- 공통 스타일 ----------------

    private fun padPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun themedColor(attr: Int, fallback: Int): Int {
        val ta = theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, fallback)
        ta.recycle()
        return c
    }

    private val colorPrimary get() = themedColor(android.R.attr.colorAccent, Color.parseColor("#1A73E8"))
    private val colorText get() = themedColor(android.R.attr.textColorPrimary, Color.BLACK)
    private val colorTextSec get() = themedColor(android.R.attr.textColorSecondary, Color.GRAY)
    private val colorCard get() = themedColor(android.R.attr.colorBackgroundFloating, Color.WHITE)
    private val colorGreen get() = Color.parseColor("#2E9E5B")
    private val colorRed get() = Color.parseColor("#D93025")

    /** 둥근 알약 버튼 */
    private fun pill(text: String, bg: Int, onClick: (View) -> Unit): TextView {
        val h = padPx(12)
        val w = padPx(20)
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(w, h, w, h)
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = padPx(60).toFloat()
            }
            setOnClickListener(onClick)
        }
    }

    /** 원형 아이콘 버튼 */
    private fun roundIcon(desc: String, iconRes: Int, tint: Int, onClick: (View) -> Unit): ImageButton {
        val size = padPx(38)
        return ImageButton(this).apply {
            contentDescription = desc
            setImageResource(iconRes)
            setColorFilter(tint)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x1A000000)
            }
            setOnClickListener(onClick)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    /** 카드 컨테이너 */
    private fun makeCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pd = padPx(18)
        setPadding(pd, pd, pd, pd)
        background = GradientDrawable().apply {
            setColor(colorCard)
            cornerRadius = padPx(16).toFloat()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = padPx(2).toFloat()
    }

    // ---------------- 메인 (상태) 화면 ----------------

    private fun buildStatusView(): LinearLayout {
        val pad = padPx(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        txtTitleMain = TextView(this).apply {
            text = getString(R.string.vpn_title)
            textSize = 22f
            setTextColor(colorText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, pad)
        }
        root.addView(txtTitleMain, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        cardBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(cardBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun renderStatusCard() {
        cardBox.removeAllViews()
        val st = JcVpnService.state

        val card = makeCard()
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_lock)
            val tint = when {
                st == "CONNECTED" -> colorGreen
                st == "DISCONNECTED" -> colorTextSec
                else -> colorPrimary
            }
            setColorFilter(tint)
        }
        card.addView(icon, LinearLayout.LayoutParams(padPx(52), padPx(52)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        fun centerText(size: Float, bold: Boolean = false, color: Int = colorText) = TextView(this).apply {
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val title = centerText(18f, bold = true)
        val sub = centerText(13f, color = colorTextSec)
        val speed = centerText(15f, bold = true, color = colorPrimary)
        val time = centerText(12f, color = colorTextSec)
        card.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = padPx(10) })
        card.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = padPx(4) })
        card.addView(speed, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = padPx(10) })
        card.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = padPx(2) })

        when (st) {
            "CONNECTED" -> {
                title.text = JcVpnService.stateProfile
                sub.text = getString(R.string.vpn_state_connected_short)
                speed.text = getString(R.string.vpn_speed_down, formatRate(JcVpnService.rxRate)) +
                    "   " + getString(R.string.vpn_speed_up, formatRate(JcVpnService.txRate))
                time.text = getString(R.string.vpn_connected_time, formatDuration(System.currentTimeMillis() - JcVpnService.connectedSince))
                val btn = pill(getString(R.string.vpn_disconnect), colorRed) { disconnect() }
                card.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = padPx(16)
                })
            }
            "CONNECTING", "AUTH", "ASSIGN_IP" -> {
                title.text = JcVpnService.stateProfile
                sub.text = getString(R.string.vpn_state_connecting_short)
                sub.setTextColor(colorPrimary)
            }
            "WAIT" -> {
                title.text = JcVpnService.stateProfile
                sub.text = getString(R.string.vpn_state_wait, JcVpnService.stateProfile)
                sub.setTextColor(colorPrimary)
            }
            "ERROR" -> {
                title.text = getString(R.string.vpn_state_error, JcVpnService.lastError)
                title.setTextColor(colorRed)
            }
            else -> {
                title.text = getString(R.string.vpn_state_disconnected)
                sub.text = getString(R.string.vpn_status_hint)
                sub.setTextColor(colorTextSec)
            }
        }
        cardBox.addView(card)

        // 프로파일 관리 버튼
        val manage = pill(getString(R.string.vpn_manage), colorPrimary) { showScreen(profiles = true) }
        cardBox.addView(manage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = padPx(20)
        })
    }

    // ---------------- 프로파일 관리 화면 ----------------

    private fun buildProfilesView(): LinearLayout {
        val pad = padPx(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // 상단 바: 뒤로 + 제목
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(roundIcon(getString(R.string.back), R.drawable.ic_arrow_back, colorText) { showScreen(profiles = false) })
        bar.addView(TextView(this).apply {
            text = getString(R.string.vpn_manage)
            textSize = 18f
            setTextColor(colorText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(padPx(12), 0, 0, 0)
        })
        root.addView(bar)

        // 추가 / 멀티 가져오기
        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, padPx(14), 0, padPx(10))
        }
        btns.addView(pill(getString(R.string.vpn_add), colorPrimary) { showAddChooser() })
        btns.addView(pill(getString(R.string.vpn_import_multi), colorTextSec) { pickMulti() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = padPx(10) })
        root.addView(btns)

        val scroll = ScrollView(this)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun refreshProfiles() {
        listBox.removeAllViews()
        val profiles = VpnProfiles.list(this)
        if (profiles.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = getString(R.string.vpn_empty)
                textSize = 14f
                setTextColor(colorTextSec)
                gravity = Gravity.CENTER
                setPadding(0, padPx(48), 0, 0)
            })
            return
        }
        val st = JcVpnService.state
        val activeId = if (st != "DISCONNECTED" && st != "ERROR") connectedProfileId() else null
        var first = true
        for (p in profiles) {
            if (!first) {
                listBox.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                        topMargin = padPx(2); bottomMargin = padPx(2)
                    }
                    setBackgroundColor(0x1A000000)
                })
            }
            first = false
            listBox.addView(profileRow(p, p.id == activeId))
        }
    }

    private fun profileRow(p: VpnProfiles.Profile, active: Boolean): View {
        val st = JcVpnService.state
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, padPx(10), 0, padPx(10))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val name = TextView(this).apply {
            text = p.name + if (p.needsAuth) " 🔑" else ""
            textSize = 15f
            setTextColor(colorText)
        }
        val sub = TextView(this).apply {
            textSize = 12f
            when {
                active && st == "CONNECTED" -> {
                    text = getString(R.string.vpn_state_connected_short)
                    setTextColor(colorGreen)
                }
                active -> {
                    text = getString(R.string.vpn_state_connecting_short)
                    setTextColor(colorPrimary)
                }
                else -> {
                    text = ""
                    setTextColor(colorTextSec)
                }
            }
        }
        col.addView(name); col.addView(sub)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 연결 / 해제 알약 버튼
        row.addView(
            if (active) pill(getString(R.string.vpn_disconnect), colorRed) { disconnect() }
            else pill(getString(R.string.vpn_connect), colorGreen) { connect(p) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = padPx(8) }
        )
        // 이름 변경
        row.addView(roundIcon(getString(R.string.vpn_rename), android.R.drawable.ic_menu_edit, colorTextSec) { renameDialog(p) })
        // 삭제
        row.addView(roundIcon(getString(R.string.action_remove), android.R.drawable.ic_delete, colorRed) { confirmDelete(p, active) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = padPx(8) })
        return row
    }

    private fun confirmDelete(p: VpnProfiles.Profile, active: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setMessage(R.string.vpn_delete_confirm)
            .setPositiveButton(R.string.action_remove) { _, _ ->
                if (active) disconnect()
                VpnProfiles.delete(this, p.id)
                refreshProfiles()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ---------------- 추가 (직접 입력 / 파일) ----------------

    private fun showAddChooser() {
        val items = arrayOf(getString(R.string.vpn_add_manual), getString(R.string.vpn_add_file))
        AlertDialog.Builder(this)
            .setTitle(R.string.vpn_add_title)
            .setItems(items) { _, which -> if (which == 0) manualAddDialog() else pickOne() }
            .show()
    }

    private fun manualAddDialog() {
        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pd = (24 * density).toInt()
            setPadding(pd, pd / 2, pd, 0)
        }
        val name = EditText(this).apply { hint = getString(R.string.vpn_name_title) }
        val body = EditText(this).apply {
            hint = getString(R.string.vpn_paste_hint)
            minLines = 6
            gravity = Gravity.TOP
            typeface = android.graphics.Typeface.MONOSPACE
        }
        box.addView(name); box.addView(body)
        AlertDialog.Builder(this)
            .setTitle(R.string.vpn_add_title)
            .setView(box)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                val nm = name.text.toString().trim()
                if (nm.isEmpty()) return@setPositiveButton
                val p = VpnProfiles.importText(this, body.text.toString(), nm)
                if (p == null) JcToast.show(this, getString(R.string.vpn_import_failed)) else refreshProfiles()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ---------------- 연결 / 해제 ----------------

    private fun connect(p: VpnProfiles.Profile) {
        // 인증이 필요한 프로파일이면 연결 전 아이디/비밀번호 입력(저장 선택)
        if (p.needsAuth && p.username.isEmpty() && p.password.isEmpty()) {
            authDialog(p)
            return
        }
        startConnect(p.id, p.username, p.password)
    }

    private fun authDialog(p: VpnProfiles.Profile) {
        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pd = (24 * density).toInt()
            setPadding(pd, pd / 2, pd, 0)
        }
        val user = EditText(this).apply { hint = "Username"; setText(p.username) }
        val pass = EditText(this).apply {
            hint = "Password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(p.password)
        }
        box.addView(user); box.addView(pass)
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setView(box)
            .setPositiveButton(R.string.vpn_connect) { _, _ ->
                VpnProfiles.saveAuth(this, p.id, user.text.toString(), pass.text.toString())
                startConnect(p.id, user.text.toString(), pass.text.toString())
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun startConnect(id: String, user: String, pass: String) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingConnectId = id
            startActivityForResult(intent, REQ_VPN_PREPARE)
            // 인증정보는 임시 저장 — 준비 승인 후 사용
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putString("vpn_pending_user", user)
                .putString("vpn_pending_pass", pass)
                .putString("vpn_pending_id", id)
                .apply()
            return
        }
        launchConnect(id, user, pass)
    }

    private fun launchConnect(id: String, user: String, pass: String) {
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("vpn_active_id", id).apply()
        val i = Intent(this, JcVpnService::class.java).apply {
            action = JcVpnService.ACTION_CONNECT
            putExtra(JcVpnService.EXTRA_PROFILE_ID, id)
            putExtra(JcVpnService.EXTRA_USERNAME, user)
            putExtra(JcVpnService.EXTRA_PASSWORD, pass)
        }
        runCatching { startForegroundService(i) }
        handler.postDelayed({ refreshAll() }, 800)
    }

    private fun disconnect() {
        runCatching {
            startService(Intent(this, JcVpnService::class.java).setAction(JcVpnService.ACTION_DISCONNECT))
        }
        getSharedPreferences("settings", MODE_PRIVATE).edit().remove("vpn_active_id").apply()
        handler.postDelayed({ refreshAll() }, 500)
    }

    // ---------------- 파일 가져오기 ----------------

    private fun pickOne() {
        runCatching {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-openvpn-profile", "text/plain", "application/octet-stream", "*/*"))
                },
                REQ_PICK_ONE
            )
        }
    }

    private fun pickMulti() {
        runCatching {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-openvpn-profile", "text/plain", "application/octet-stream", "*/*"))
                },
                REQ_PICK_MULTI
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PICK_ONE -> {
                val uri = data?.data ?: return
                // 파일 이름을 기본값으로 한 이름 입력 다이얼로그
                val defName = runCatching { uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".ovpn") }.getOrNull() ?: "VPN"
                val edit = EditText(this).apply { setText(defName) }
                val density = resources.displayMetrics.density
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val pd = (24 * density).toInt()
                    setPadding(pd, pd / 2, pd, 0)
                    addView(edit)
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.vpn_name_title)
                    .setView(box)
                    .setPositiveButton(R.string.btn_ok) { _, _ ->
                        val p = VpnProfiles.import(this, uri, edit.text.toString())
                        if (p == null) JcToast.show(this, getString(R.string.vpn_import_failed)) else refreshProfiles()
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }
            REQ_PICK_MULTI -> {
                var count = 0
                val d = data ?: return
                // 멀티: 파일 이름으로 이름 넣기
                val clip = d.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) {
                        val uri = clip.getItemAt(i).uri
                        val nm = runCatching { uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".ovpn") }.getOrNull()
                        if (VpnProfiles.import(this, uri, nm) != null) count++
                    }
                } else if (d.data != null) {
                    val uri = d.data!!
                    val nm = runCatching { uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".ovpn") }.getOrNull()
                    if (VpnProfiles.import(this, uri, nm) != null) count++
                }
                JcToast.show(this, getString(R.string.vpn_imported, count))
                refreshProfiles()
            }
            REQ_VPN_PREPARE -> {
                val sp = getSharedPreferences("settings", MODE_PRIVATE)
                val id = sp.getString("vpn_pending_id", null)
                val user = sp.getString("vpn_pending_user", "") ?: ""
                val pass = sp.getString("vpn_pending_pass", "") ?: ""
                sp.edit().remove("vpn_pending_id").remove("vpn_pending_user").remove("vpn_pending_pass").apply()
                if (id != null) launchConnect(id, user, pass)
            }
        }
    }

    private fun renameDialog(p: VpnProfiles.Profile) {
        val edit = EditText(this).apply { setText(p.name) }
        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pd = (24 * density).toInt()
            setPadding(pd, pd / 2, pd, 0)
            addView(edit)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.vpn_rename)
            .setView(box)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                VpnProfiles.rename(this, p.id, edit.text.toString())
                refreshProfiles()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ---------------- 포맷 ----------------

    private fun connectedProfileId(): String? =
        getSharedPreferences("settings", MODE_PRIVATE).getString("vpn_active_id", null)

    private fun formatRate(bytesPerSec: Long): String {
        if (bytesPerSec < 1024) return "$bytesPerSec B/s"
        val kb = bytesPerSec / 1024.0
        if (kb < 1024) return "%.0f KB/s".format(kb)
        return "%.1f MB/s".format(kb / 1024.0)
    }

    private fun formatDuration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
