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
import android.widget.Switch
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
    private lateinit var scrollMain: ScrollView
    private lateinit var consoleBox: LinearLayout
    private lateinit var txtLog: TextView
    private lateinit var btnLog: TextView
    private var logVisible = false

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
        JcVpnService.onLog = { line -> runOnUiThread { appendLogLine(line) } }
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
        JcVpnService.onLog = null
        super.onDestroy()
    }

    /** 시스템 뒤로: 프로파일 관리 화면이면 메인으로, 메인이면 종료 */
    override fun onBackPressed() {
        if (showProfiles) showScreen(profiles = false) else super.onBackPressed()
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

    // ---------------- 공통 스타일 (JcUi 통합 키트에 위임) ----------------

    private fun padPx(dp: Int) = JcUi.dp(this, dp)

    private val colorPrimary get() = JcUi.blue
    private val colorText get() = JcUi.textPrimary(this)
    private val colorTextSec get() = JcUi.slate
    private val colorCard get() = JcUi.cardBg(this)
    private val colorGreen get() = JcUi.green
    private val colorRed get() = JcUi.red

    /** 플랫 라벨 버튼 (탭/즐겨찾기 컨셉: 투명+ripple, 아이콘+텍스트) */
    private fun pill(text: String, color: Int, iconRes: Int = 0, onClick: (View) -> Unit): TextView =
        JcUi.pill(this, text, color, iconRes, onClick)

    /** 원형 아이콘 버튼 */
    private fun roundIcon(desc: String, iconRes: Int, tint: Int, onClick: (View) -> Unit): ImageButton =
        JcUi.circleIcon(this, desc, iconRes, tint, onClick)

    /** 카드 컨테이너 */
    private fun makeCard(): LinearLayout = JcUi.card(this)

    // ---------------- 메인 (상태) 화면 ----------------

    private fun buildStatusView(): LinearLayout {
        val pad = padPx(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // 상단 바: 뒤로(화면 닫기) + 제목
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(roundIcon(getString(R.string.back), R.drawable.ic_arrow_back, colorText) { finish() })
        txtTitleMain = TextView(this).apply {
            text = getString(R.string.vpn_title)
            textSize = 22f
            setTextColor(colorText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(padPx(12), 0, 0, 0)
        }
        bar.addView(txtTitleMain)
        root.addView(bar)

        // 내용 전체를 스크롤 — 로그 콘솔이 길어져도 화면 밖으로 안 나감
        scrollMain = ScrollView(this)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollMain.addView(inner)

        cardBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, 0)
        }
        inner.addView(cardBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // [프로파일 관리] [로그] 버튼 행
        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad, 0, 0)
        }
        pillRow.addView(pill(getString(R.string.vpn_manage), colorPrimary, R.drawable.ic_settings) { showScreen(profiles = true) })
        btnLog = pill(getString(R.string.vpn_log), colorTextSec, R.drawable.ic_code) { toggleLog() }
        pillRow.addView(btnLog, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = padPx(10)
        })
        inner.addView(pillRow)

        // 사설 IP(LAN) VPN 경유 설정 카드
        inner.addView(buildPrivateIpSetting(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = padPx(12)
        })

        // 로그 콘솔 (터미널 스타일, 기본 숨김)
        txtLog = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF9CCC65.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
        }
        consoleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pd = padPx(12)
            setPadding(pd, pd, pd, pd)
            background = GradientDrawable().apply {
                setColor(0xFF101418.toInt())
                cornerRadius = padPx(12).toFloat()
            }
            visibility = View.GONE
            addView(txtLog, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        inner.addView(consoleBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = padPx(12)
        })

        root.addView(scrollMain, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    // ---------------- 로그 콘솔 ----------------

    /** 사설 IP(LAN) VPN 경유 토글 — 켜면 192.168.x.x 등도 VPN 통과, 끄면(기본) VPN 우회 후 직접 접속 */
    private fun buildPrivateIpSetting(): LinearLayout {
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val card = makeCard()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(this).apply {
            text = getString(R.string.vpn_private_ip_title)
            textSize = 15f
            setTextColor(colorText)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val summary = TextView(this).apply {
            textSize = 12f
            setTextColor(colorTextSec)
        }
        texts.addView(title)
        texts.addView(summary)
        val sw = Switch(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = padPx(12) }
        }
        fun refresh() {
            val on = sp.getBoolean("vpn_private_ip", false)
            sw.isChecked = on
            summary.text = getString(if (on) R.string.vpn_private_ip_on else R.string.vpn_private_ip_off)
        }
        sw.setOnCheckedChangeListener { _, on ->
            sp.edit().putBoolean("vpn_private_ip", on).apply()
            refresh()
        }
        refresh()
        row.addView(texts)
        row.addView(sw)
        card.addView(row)
        return card
    }

    private fun toggleLog() {
        logVisible = !logVisible
        if (logVisible) {
            txtLog.text = JcVpnService.logLines.joinToString("\n", postfix = "\n")
            consoleBox.visibility = View.VISIBLE
            btnLog.text = getString(R.string.vpn_log_hide)
            handler.post { scrollMain.fullScroll(ScrollView.FOCUS_DOWN) }
        } else {
            consoleBox.visibility = View.GONE
            btnLog.text = getString(R.string.vpn_log)
        }
    }

    private fun appendLogLine(line: String) {
        if (!logVisible || !::txtLog.isInitialized) return
        txtLog.append("$line\n")
        handler.post { scrollMain.fullScroll(ScrollView.FOCUS_DOWN) }
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
                val btn = pill(getString(R.string.vpn_disconnect), colorRed, R.drawable.ic_close) { disconnect() }
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
        btns.addView(pill(getString(R.string.vpn_add), colorPrimary, R.drawable.ic_add) { showAddChooser() })
        btns.addView(pill(getString(R.string.vpn_import_multi), colorTextSec, R.drawable.ic_folder) { pickMulti() },
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
        // ERROR 도 실패한 항목으로 표시 (서비스는 이미 멈췄지만 어떤 프로파일이 실패했는지 보여줘야 함)
        val activeId = if (st != "DISCONNECTED") connectedProfileId() else null
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
        // 부제목: 국가/파일명 + 연결 상태
        val info = StringBuilder()
        if (p.country.length == 2) info.append(flagEmoji(p.country)).append(' ').append(p.country)
        if (p.fileName.isNotEmpty()) {
            if (info.isNotEmpty()) info.append(" · ")
            info.append(p.fileName)
        }
        val sub = TextView(this).apply {
            textSize = 12f
            when {
                active && st == "CONNECTED" -> {
                    if (info.isNotEmpty()) info.append(" · ")
                    info.append(getString(R.string.vpn_state_connected_short))
                    text = info.toString()
                    setTextColor(colorGreen)
                }
                active && st == "ERROR" -> {
                    if (info.isNotEmpty()) info.append(" · ")
                    info.append(getString(R.string.vpn_failed))
                    text = info.toString()
                    setTextColor(colorRed)
                }
                active -> {
                    if (info.isNotEmpty()) info.append(" · ")
                    info.append(getString(R.string.vpn_state_connecting_short))
                    text = info.toString()
                    setTextColor(colorPrimary)
                }
                else -> {
                    text = info.toString()
                    setTextColor(colorTextSec)
                }
            }
        }
        col.addView(name); col.addView(sub)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 연결 / 해제 알약 버튼 — 실패 시에는 다시 연결(재시도)
        row.addView(
            when {
                active && st == "ERROR" -> pill(getString(R.string.vpn_connect), colorGreen, R.drawable.ic_play) { connect(p) }
                active -> pill(getString(R.string.vpn_disconnect), colorRed, R.drawable.ic_close) { disconnect() }
                else -> pill(getString(R.string.vpn_connect), colorGreen, R.drawable.ic_play) { connect(p) }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = padPx(8) }
        )
        // 이름/인증정보 수정
        row.addView(roundIcon(getString(R.string.vpn_edit), android.R.drawable.ic_menu_edit, colorTextSec) { editDialog(p) })
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
        val user = EditText(this).apply { hint = getString(R.string.vpn_username); setText(p.username) }
        val pass = passwordBox(getString(R.string.vpn_password), p.password)
        box.addView(user); box.addView(pass.view)
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setView(box)
            .setPositiveButton(R.string.vpn_connect) { _, _ ->
                VpnProfiles.saveAuth(this, p.id, user.text.toString(), pass.edit.text.toString())
                startConnect(p.id, user.text.toString(), pass.edit.text.toString())
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** 비밀번호 입력 + 눈알(표시 토글) 을 담은 컨테이너 */
    private class PassBox(val edit: EditText, val view: FrameLayout)

    private fun passwordBox(hint: String, initial: String): PassBox {
        val edit = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(initial)
            setPadding(paddingLeft, paddingTop, padPx(46), paddingBottom)
        }
        // 표시 상태는 별도 플래그로 추적 — inputType 비트 검사는
        // PASSWORD(0x80)/VISIBLE_PASSWORD(0x90) 비트가 겹쳐 항상 참이 되는 버그가 있음
        var shown = false
        val eye = TextView(this).apply {
            text = "👁"
            textSize = 18f
            gravity = Gravity.CENTER
            alpha = 0.55f
            setOnClickListener {
                shown = !shown
                edit.inputType = InputType.TYPE_CLASS_TEXT or
                    (if (shown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                     else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                edit.typeface = android.graphics.Typeface.DEFAULT
                edit.setSelection(edit.text.length)
                alpha = if (shown) 1f else 0.55f
            }
        }
        val fl = FrameLayout(this).apply {
            addView(edit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(eye, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                marginEnd = padPx(10)
            })
        }
        return PassBox(edit, fl)
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
                // SAF 에서는 lastPathSegment 가 msf:123 같은 ID 일 수 있으니 표시명 우선 조회
                val disp = VpnProfiles.displayNameOf(this, uri)
                val defName = disp?.removeSuffix(".ovpn") ?: "VPN"
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
                val importedIds = mutableListOf<String>()
                val d = data ?: return
                // 멀티: 파일 이름으로 이름 넣기
                val clip = d.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) {
                        val uri = clip.getItemAt(i).uri
                        val nm = runCatching { uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".ovpn") }.getOrNull()
                        VpnProfiles.import(this, uri, nm)?.let { importedIds += it.id; count++ }
                    }
                } else if (d.data != null) {
                    val uri = d.data!!
                    val nm = runCatching { uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".ovpn") }.getOrNull()
                    VpnProfiles.import(this, uri, nm)?.let { importedIds += it.id; count++ }
                }
                JcToast.show(this, getString(R.string.vpn_imported, count))
                refreshProfiles()
                // 가져온 프로파일 전체에 공통 아이디/비밀번호 적용 옵션
                if (importedIds.isNotEmpty()) multiAuthDialog(importedIds)
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

    /** 이름 + 크리덴셜 수정 다이얼로그 (연필 버튼) */
    private fun editDialog(p: VpnProfiles.Profile) {
        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pd = (24 * density).toInt()
            setPadding(pd, pd / 2, pd, 0)
        }
        val name = EditText(this).apply { hint = getString(R.string.vpn_name_title); setText(p.name) }
        val user = EditText(this).apply { hint = getString(R.string.vpn_username); setText(p.username) }
        val pass = passwordBox(getString(R.string.vpn_password), p.password)
        box.addView(name); box.addView(user); box.addView(pass.view)
        AlertDialog.Builder(this)
            .setTitle(R.string.vpn_edit)
            .setView(box)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                val nm = name.text.toString().trim()
                if (nm.isNotEmpty() && nm != p.name) VpnProfiles.rename(this, p.id, nm)
                VpnProfiles.saveAuth(this, p.id, user.text.toString(), pass.edit.text.toString())
                refreshProfiles()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** 멀티 import 후 가져온 프로파일 전체에 공통 아이디/비밀번호 적용 */
    private fun multiAuthDialog(ids: List<String>) {
        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pd = (24 * density).toInt()
            setPadding(pd, pd / 2, pd, 0)
        }
        val user = EditText(this).apply { hint = getString(R.string.vpn_username) }
        val pass = passwordBox(getString(R.string.vpn_password), "")
        box.addView(user); box.addView(pass.view)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.vpn_multi_auth_title))
            .setMessage(getString(R.string.vpn_multi_auth_msg, ids.size))
            .setView(box)
            .setPositiveButton(R.string.vpn_multi_auth_apply) { _, _ ->
                val u = user.text.toString()
                val p = pass.edit.text.toString()
                if (u.isNotEmpty() || p.isNotEmpty()) {
                    ids.forEach { VpnProfiles.saveAuth(this, it, u, p) }
                    refreshProfiles()
                }
            }
            .setNegativeButton(R.string.vpn_multi_auth_skip, null)
            .show()
    }

    // ---------------- 포맷 ----------------

    private fun connectedProfileId(): String? =
        getSharedPreferences("settings", MODE_PRIVATE).getString("vpn_active_id", null)

    /** ISO 국가 코드 → 깃발 이모지 (예: KR → 🇰🇷) */
    private fun flagEmoji(code: String): String {
        if (code.length != 2) return ""
        val base = 0x1F1E6
        return String(Character.toChars(base + (code[0].uppercaseChar() - 'A'))) +
            String(Character.toChars(base + (code[1].uppercaseChar() - 'A')))
    }

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
