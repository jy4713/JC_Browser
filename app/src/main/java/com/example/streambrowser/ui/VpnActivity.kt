package com.example.streambrowser.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.streambrowser.R
import com.example.streambrowser.util.JcToast
import com.example.streambrowser.vpn.JcVpnService
import com.example.streambrowser.vpn.VpnProfiles

/**
 * VPN 프로파일 관리 화면
 * - .ovpn 추가(파일 1개 + 이름 입력) / 멀티 import(파일 이름으로 이름)
 * - 이름 변경 / 삭제 / 연결(인증정보 미리 저장 시 그대로, 없으면 Auth 요구 시 빈 값 시도)
 * - 시스템 VPN 권한(VpnService.prepare) 승인 후 연결
 */
class VpnActivity : Activity() {

    companion object {
        private const val REQ_PICK_ONE = 11
        private const val REQ_PICK_MULTI = 12
        private const val REQ_VPN_PREPARE = 13
    }

    private lateinit var listBox: LinearLayout
    private lateinit var txtStatus: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var pendingConnectId: String? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.example.streambrowser.util.LocaleHelper.wrap(com.example.streambrowser.util.ThemeHelper.wrap(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.streambrowser.util.ThemeHelper.apply(this)
        VpnProfiles.init(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        txtStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, pad / 2)
        }
        root.addView(txtStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 상단 버튼: 추가 / 멀티 가져오기 / 연결 해제
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, l: View.OnClickListener) {
            btns.addView(Button(this).apply { text = label; setOnClickListener(l) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (6 * resources.displayMetrics.density).toInt()
                })
        }
        btn(getString(R.string.vpn_add)) { pickOne() }
        btn(getString(R.string.vpn_import_multi)) { pickMulti() }
        btn(getString(R.string.vpn_disconnect)) { disconnect() }
        root.addView(btns)

        val scroll = ScrollView(this)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        refresh()

        JcVpnService.onStateChange = { runOnUiThread { refresh() } }
    }

    override fun onDestroy() {
        JcVpnService.onStateChange = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------- 목록 ----------------

    private fun refresh() {
        val st = JcVpnService.state
        txtStatus.text = when (st) {
            "CONNECTED" -> getString(R.string.vpn_state_connected, JcVpnService.stateProfile)
            "CONNECTING", "AUTH", "ASSIGN_IP" -> getString(R.string.vpn_state_connecting, JcVpnService.stateProfile)
            "WAIT" -> getString(R.string.vpn_state_wait, JcVpnService.stateProfile)
            "ERROR" -> getString(R.string.vpn_state_error, JcVpnService.lastError)
            else -> getString(R.string.vpn_state_disconnected)
        }
        listBox.removeAllViews()
        val profiles = VpnProfiles.list(this)
        if (profiles.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = getString(R.string.vpn_empty)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, padPx(48), 0, 0)
            })
            return
        }
        val activeId = if (st != "DISCONNECTED" && st != "ERROR") connectedProfileId() else null
        for (p in profiles) {
            listBox.addView(row(p, p.id == activeId))
        }
    }

    private fun connectedProfileId(): String? =
        getSharedPreferences("settings", MODE_PRIVATE).getString("vpn_active_id", null)

    private fun padPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun row(p: VpnProfiles.Profile, active: Boolean): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, padPx(10), 0, padPx(10))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val name = TextView(this).apply {
            text = p.name + if (p.needsAuth) " 🔑" else ""
            textSize = 15f
        }
        val sub = TextView(this).apply {
            text = if (active) getString(R.string.vpn_state_connected_short) else ""
            textSize = 12f
        }
        col.addView(name); col.addView(sub)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        fun ib(label: String, onClick: View.OnClickListener): ImageButton {
            val b = ImageButton(this).apply {
                contentDescription = label
                setImageResource(android.R.drawable.ic_menu_edit)
                setOnClickListener(onClick)
                background = null
            }
            row.addView(b, LinearLayout.LayoutParams(padPx(40), padPx(40)))
            return b
        }

        // 연결/해제
        ib(if (active) getString(R.string.vpn_disconnect) else getString(R.string.vpn_connect)) {
            if (active) disconnect() else connect(p)
        }.setImageResource(
            if (active) android.R.drawable.ic_menu_close_clear_cancel
            else android.R.drawable.ic_menu_set_as
        )
        // 이름 변경
        ib(getString(R.string.vpn_rename)) { renameDialog(p) }
        // 삭제
        ib(getString(R.string.action_remove)) {
            AlertDialog.Builder(this)
                .setTitle(p.name)
                .setMessage(R.string.vpn_delete_confirm)
                .setPositiveButton(R.string.action_remove) { _, _ ->
                    if (active) disconnect()
                    VpnProfiles.delete(this, p.id)
                    refresh()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }.setImageResource(android.R.drawable.ic_delete)
        return row
    }

    // ---------------- 연결 ----------------

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
        handler.postDelayed({ refresh() }, 800)
    }

    private fun disconnect() {
        runCatching {
            startService(Intent(this, JcVpnService::class.java).setAction(JcVpnService.ACTION_DISCONNECT))
        }
        getSharedPreferences("settings", MODE_PRIVATE).edit().remove("vpn_active_id").apply()
        handler.postDelayed({ refresh() }, 500)
    }

    // ---------------- 추가 / 가져오기 ----------------

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
                        if (p == null) JcToast.show(this, getString(R.string.vpn_import_failed)) else refresh()
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
                refresh()
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

    // ImageButton 리소스 미리 임포트 없이 android.R drawable 사용 (빌트인)

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
                refresh()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
