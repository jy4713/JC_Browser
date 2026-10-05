package com.example.streambrowser

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.streambrowser.browser.AdBlocker
import com.example.streambrowser.browser.SniffingWebViewClient
import com.example.streambrowser.browser.VideoJsBridge
import com.example.streambrowser.browser.VideoStore
import com.example.streambrowser.db.BookmarkRepo
import com.example.streambrowser.db.HistoryRepo
import com.example.streambrowser.download.VideoAdapter
import java.io.File

class MainActivity : Activity() {

    private data class Tab(val web: WebView, var incognito: Boolean = false, var fromWindow: Boolean = false)

    private val tabs = mutableListOf<Tab>()
    private var current = 0

    private lateinit var container: FrameLayout
    private lateinit var editUrl: EditText
    private lateinit var btnVideos: android.widget.ImageButton
    private lateinit var btnNavTabs: android.widget.ImageButton
    private lateinit var btnNavBack: ImageButton
    private lateinit var btnNavForward: ImageButton
    private lateinit var badgeVideos: android.widget.TextView
    private lateinit var badgeTabs: android.widget.TextView
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var videoList: RecyclerView
    private lateinit var adapter: VideoAdapter
    private lateinit var prefs: SharedPreferences
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var chromeClient: WebChromeClient

    /** 풀스크린 영상 재생 상태 (Chrome의 custom view 처리) */
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private val HOME = "https://www.google.com"
    private val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        AdBlocker.init(this)

        container = findViewById(R.id.webContainer)
        editUrl = findViewById(R.id.editUrl)
        btnVideos = findViewById(R.id.btnVideos)
        btnNavTabs = findViewById(R.id.btnNavTabs)
        badgeVideos = findViewById(R.id.badgeVideos)
        badgeTabs = findViewById(R.id.badgeTabs)
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        videoList = findViewById(R.id.videoList)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        adapter = VideoAdapter()
        videoList.layoutManager = LinearLayoutManager(this)
        videoList.adapter = adapter

        // Chrome 제스처: 주소창 좌우 스와이프로 탭 전환
        val tabFling = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (Math.abs(dx) > 120 && Math.abs(vx) > 250 && Math.abs(dx) > Math.abs(dy) * 1.5) {
                    if (dx < 0 && current < tabs.size - 1) {           // 왼쪽 밀기 → 다음 탭
                        current++; showCurrent(); return true
                    }
                    if (dx > 0 && current > 0) {                       // 오른쪽 밀기 → 이전 탭
                        current--; showCurrent(); return true
                    }
                }
                return false
            }
        })
        editUrl.setOnTouchListener { _, ev -> tabFling.onTouchEvent(ev); false }

        // 주소창
        editUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { loadUrl(); true } else false
        }

        // 영상 패널 토글
        btnVideos.setOnClickListener {
            videoList.visibility = if (videoList.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // 하단 툴팁
        btnNavBack = findViewById(R.id.btnNavBack)
        btnNavForward = findViewById(R.id.btnNavForward)
        btnNavBack.setOnClickListener {
            onBackPressed()
        }
        btnNavForward.setOnClickListener {
            current()?.let { if (it.web.canGoForward()) it.web.goForward() }
        }
        findViewById<android.widget.ImageButton>(R.id.btnNavRefresh).setOnClickListener { current()?.web?.reload() }
        findViewById<android.widget.ImageButton>(R.id.btnNavHome).setOnClickListener { current()?.web?.loadUrl(HOME) }
        btnNavTabs.setOnClickListener { showTabDialog() }
        findViewById<android.widget.ImageButton>(R.id.btnNavMenu).setOnClickListener { showMainMenu() }

        // 페이지 내 찾기
        findInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                current()?.web?.findAllAsync(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        findViewById<android.widget.ImageButton>(R.id.btnFindNext).setOnClickListener { current()?.web?.findNext(true) }
        findViewById<android.widget.ImageButton>(R.id.btnFindPrev).setOnClickListener { current()?.web?.findNext(false) }
        findViewById<android.widget.ImageButton>(R.id.btnFindClose).setOnClickListener {
            current()?.web?.clearMatches()
            findBar.visibility = View.GONE
        }

        VideoStore.listener = {
            runOnUiThread {
                val n = VideoStore.videos.size
                badgeVideos.text = n.toString()
                badgeVideos.visibility = if (n > 0) View.VISIBLE else View.GONE
                adapter.notifyDataSetChanged()
            }
        }

        // WebView 자체를 사용할 수 없는 기기(구형/손상된 WebView) 방어
        val webViewAvailable = runCatching {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    WebView.getCurrentWebViewPackage() != null
        }.getOrDefault(true)
        if (!webViewAvailable) {
            showWebViewErrorAndFinish()
            return
        }

        createTab(HOME) ?: run { showWebViewErrorAndFinish(); return }

        // 시작 시 탭 복원
        if (prefs.getBoolean("restore_tabs", true)) {
            val saved = (prefs.getString("saved_tabs", "") ?: "").split("\n")
                .filter { it.isNotBlank() && it != HOME }
            saved.take(10).forEach { createTab(it) }
            prefs.getInt("saved_index", 0).coerceIn(0, tabs.size - 1).let {
                current = it
                showCurrent()
            }
        }

        intent?.data?.let { uri -> createTab(uri.toString()) }
    }

    // ------------------------------------------------------- WebView 충돌 방어

    private fun showWebViewErrorAndFinish() {
        AlertDialog.Builder(this)
            .setTitle("WebView 오류")
            .setMessage(
                "기기의 'Android System WebView'에 문제가 있어 브라우저를 실행할 수 없습니다.\n\n" +
                        "1. Play 스토어에서 'Android System WebView' 검색 후 업데이트\n" +
                        "2. Chrome 업데이트\n" +
                        "3. 기기 설정 > 애플리케이션 > Android System WebView > 저장공간 > 캐시 삭제\n\n" +
                        "위 조치 후 다시 실행해 주세요."
            )
            .setCancelable(false)
            .setPositiveButton("종료") { _, _ -> finish() }
            .show()
    }

    // ------------------------------------------------------- 탭 관리

    private fun current(): Tab? = tabs.getOrNull(current)

    private fun createTab(url: String, incognito: Boolean = false): Tab? {
        val wv = try {
            WebView(this)
        } catch (t: Throwable) {
            // WebView 인스턴스 생성 실패 (공급자 오류 등)
            showWebViewErrorAndFinish()
            return null
        }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = if (prefs.getBoolean("desktop", false)) UA_DESKTOP else UA_MOBILE
            textZoom = prefs.getInt("text_zoom", 100)
            setSupportMultipleWindows(true)
            applyDarkMode(this)
            // Chrome Safe Browsing: 피싱/악성코드 사이트 탐지 (API 26+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { safeBrowsingEnabled = true }
            }
        }
        // 렌더 프로세스 우선순위: 화면에 보일 때 중요도 유지, 백그라운드에선 해제 (메모리 안정성)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true) }
        }
        wv.webViewClient = SniffingWebViewClient(
            onPageStartedCb = { view, url ->
                runOnUiThread {
                    if (view == current()?.web) {
                        editUrl.setText(url)
                        // 새 페이지로 이동하면 영상/이미지 목록 리셋 (이 페이지 것만 표시)
                        VideoStore.clear()
                        updateNavButtons()
                    }
                }
            },
            onPageFinishedCb = { view, url ->
                runOnUiThread {
                    if (view == current()?.web) {
                        editUrl.setText(url)
                        updateNavButtons()
                        val tab = tabs.firstOrNull { it.web == view }
                        if (tab?.incognito != true) {
                            runCatching { HistoryRepo.add(this, view.title ?: "", url) }
                        }
                    }
                    // 페이지 내 video/audio 소스 스캐너 주입
                    runCatching { view.evaluateJavascript(VideoJsBridge.SCANNER_JS, null) }
                }
            },
            onRenderProcessGoneCb = { gone -> recoverRenderProcess(gone) }
        )
        chromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                val newTab = createTab("") ?: return false
                newTab.fromWindow = true
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newTab.web
                resultMsg.sendToTarget()
                return true
            }

            // Chrome 방식 풀스크린 영상: 화면 전체로 전환하고 주소창/하단바 숨김
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback
                topBar.visibility = View.GONE
                bottomBar.visibility = View.GONE
                findBar.visibility = View.GONE
                container.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }

            override fun onHideCustomView() {
                fullscreenView?.let { runCatching { container.removeView(it) } }
                fullscreenView = null
                fullscreenCallback?.onCustomViewHidden()
                fullscreenCallback = null
                topBar.visibility = View.VISIBLE
                bottomBar.visibility = View.VISIBLE
            }
        }
        wv.webChromeClient = chromeClient
        // Chrome/Brave 방식 롱프레스 메뉴: 링크/이미지 액션
        wv.setOnLongClickListener { showHitMenu(wv) }
        val tab = Tab(wv, incognito)
        tabs.add(tab)
        current = tabs.size - 1
        // JS -> 안드로이드 다리: blob/MSE 등 숨은 영상 소스 수집용
        runCatching { wv.addJavascriptInterface(VideoJsBridge(), "StreamBrowser") }
        if (url.isNotEmpty()) wv.loadUrl(url)
        showCurrent()
        return tab
    }

    private fun closeTab(index: Int) {
        if (tabs.size <= 1) {
            Toast.makeText(this, "마지막 탭은 닫을 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        tabs[index].web.destroy()
        tabs.removeAt(index)
        if (current >= tabs.size) current = tabs.size - 1
        showCurrent()
    }

    // ------------------------------------------------------- Chrome/Brave 롱프레스 메뉴

    /** 링크/이미지 롱프레스 시 표시할 액션 메뉴 */
    private fun showHitMenu(wv: WebView): Boolean {
        val r = wv.hitTestResult ?: return false
        val extra = r.extra ?: return false
        when (r.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE,
            WebView.HitTestResult.EMAIL_TYPE,
            WebView.HitTestResult.PHONE_TYPE,
            WebView.HitTestResult.GEO_TYPE -> {
                showLinkMenu(extra)
                return true
            }
            WebView.HitTestResult.IMAGE_TYPE,
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                showImageMenu(extra, wv.url ?: "")
                return true
            }
        }
        return false
    }

    private fun showLinkMenu(url: String) {
        val opts = arrayOf("새 탭으로 열기", "시크릿 탭으로 열기", "링크 복사", "공유")
        AlertDialog.Builder(this)
            .setTitle(url.let { if (it.length > 60) it.take(60) + "…" else it })
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> createTab(url)
                    1 -> createTab(url, incognito = true)
                    2 -> copyText(url, "링크가 복사되었습니다.")
                    3 -> {
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                        }
                        startActivity(Intent.createChooser(i, "공유"))
                    }
                }
            }
            .show()
    }

    private fun showImageMenu(imgUrl: String, page: String) {
        val opts = arrayOf("이미지 새 탭으로 열기", "이미지 저장 (다운로드)", "이미지 주소 복사")
        AlertDialog.Builder(this)
            .setTitle("이미지")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> createTab(imgUrl)
                    1 -> {
                        val seg = imgUrl.substringBefore("?").substringAfterLast("/")
                        val name = seg.substringBeforeLast(".").ifBlank { "image_" + System.currentTimeMillis() }
                        val ext = Regex("\\.([A-Za-z0-9]{2,5})$").find(seg)?.groupValues?.get(1) ?: "jpg"
                        com.example.streambrowser.download.ImageDownloader.download(this, imgUrl, page, name, ext)
                        Toast.makeText(this, "이미지 다운로드를 시작했습니다.", Toast.LENGTH_SHORT).show()
                    }
                    2 -> copyText(imgUrl, "이미지 주소가 복사되었습니다.")
                }
            }
            .show()
    }

    private fun copyText(text: String, msg: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showCurrent() {
        container.removeAllViews()
        current()?.let { container.addView(it.web) }
        editUrl.setText(current()?.web?.url ?: "")
        badgeTabs.text = tabs.size.toString()
        updateNavButtons()
    }

    /** 뒤로/앞으로 가능 여부에 따라 버튼을 흐리게 표시 */
    private fun updateNavButtons() {
        val wv = current()?.web
        btnNavBack.alpha = if (wv != null && (wv.canGoBack() || (current()?.fromWindow == true && tabs.size > 1))) 1.0f else 0.3f
        btnNavForward.alpha = if (wv != null && wv.canGoForward()) 1.0f else 0.3f
    }

    /** 렌더 프로세스가 죽은 탭을 같은 URL로 새 WebView를 만들어 복구 (Chrome 방식, 앱 크래시 방지) */
    private fun recoverRenderProcess(goneView: WebView) {
        runOnUiThread {
            val idx = tabs.indexOfFirst { it.web == goneView }
            if (idx < 0) return@runOnUiThread
            val url = goneView.url
            val incog = tabs[idx].incognito
            runCatching { container.removeView(goneView) }
            runCatching { goneView.destroy() }
            tabs.removeAt(idx)
            val nt = createTab(
                if (url.isNullOrBlank()) HOME else url,
                incognito = incog
            ) ?: run {
                if (tabs.isEmpty()) {
                    current = 0
                    createTab(HOME)
                }
                return@runOnUiThread
            }
            // 복구한 탭을 원래 위치에 다시 끼움
            tabs.removeAt(tabs.size - 1)
            tabs.add(idx.coerceAtMost(tabs.size), nt)
            current = idx.coerceAtMost(tabs.size - 1)
            showCurrent()
            Toast.makeText(this, "페이지 렌더링이 중단되어 새로 고쳤습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTabDialog() {
        val dlg = Dialog(this)
        dlg.setContentView(R.layout.dialog_tabs)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val txtCount = dlg.findViewById<TextView>(R.id.txtTabCount)
        val list = dlg.findViewById<RecyclerView>(R.id.listTabs)
        list.layoutManager = LinearLayoutManager(this)

        lateinit var tabAdapter: TabSheetAdapter
        tabAdapter = TabSheetAdapter(
            onSelect = { i ->
                current = i
                showCurrent()
                dlg.dismiss()
            },
            onClose = { i ->
                closeTab(i)
                txtCount.text = "탭 ${tabs.size}"
                tabAdapter.submit(tabs)
            }
        )
        list.adapter = tabAdapter
        tabAdapter.submit(tabs)
        txtCount.text = "탭 ${tabs.size}"

        dlg.findViewById<ImageButton>(R.id.btnTabsClose).setOnClickListener { dlg.dismiss() }
        dlg.findViewById<Button>(R.id.btnNewTab).setOnClickListener {
            createTab(HOME)
            dlg.dismiss()
        }
        dlg.findViewById<Button>(R.id.btnCloseCurrent).setOnClickListener {
            closeTab(current)
            txtCount.text = "탭 ${tabs.size}"
            tabAdapter.submit(tabs)
        }
        dlg.show()
    }

    /** 탭 시트 어댑터: 도메인 아바타 + 제목/URL + 개별 닫기 */
    private inner class TabSheetAdapter(
        private val onSelect: (Int) -> Unit,
        private val onClose: (Int) -> Unit
    ) : RecyclerView.Adapter<TabSheetAdapter.VH>() {

        private var items = listOf<Tab>()

        fun submit(t: List<Tab>) {
            items = t.toList()
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val avatar: TextView = v.findViewById(R.id.tabAvatar)
            val title: TextView = v.findViewById(R.id.tabTitle)
            val url: TextView = v.findViewById(R.id.tabUrl)
            val close: ImageButton = v.findViewById(R.id.btnTabClose)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val t = items[position]
            val base = t.web.title ?: t.web.url ?: "새 탭"
            val title = if (base.length > 60) base.substring(0, 60) + "…" else base
            h.title.text = (if (t.incognito) "🔒 " else "") + title
            h.url.text = t.web.url ?: ""
            h.itemView.setBackgroundColor(
                if (position == current) 0xFFE8F0FE.toInt() else Color.TRANSPARENT
            )

            // 도메인 첫 글자 + 색상 아바타
            val host = runCatching { Uri.parse(t.web.url).host ?: "" }.getOrDefault("")
            val letter = (host.firstOrNull { it.isLetterOrDigit() } ?: '•').uppercaseChar().toString()
            val hue = ((host.hashCode() % 360) + 360) % 360f
            h.avatar.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.85f)))
            }
            h.avatar.text = letter

            h.itemView.setOnClickListener { onSelect(position) }
            h.close.setOnClickListener { onClose(position) }
        }
    }

    // ------------------------------------------------------- 메뉴

    private fun showMainMenu() {
        val popup = PopupMenu(this, findViewById(R.id.btnNavMenu))
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
        popup.menu.findItem(R.id.menu_desktop).isChecked = prefs.getBoolean("desktop", false)
        popup.menu.findItem(R.id.menu_dark).isChecked = prefs.getBoolean("dark", false)
        popup.menu.findItem(R.id.menu_adblock).isChecked = AdBlocker.enabled
        popup.menu.findItem(R.id.menu_restore_tabs).isChecked = prefs.getBoolean("restore_tabs", true)
        popup.setOnMenuItemClickListener { item ->
            handleMenu(item.itemId)
            true
        }
        popup.show()
    }

    private fun handleMenu(id: Int) {
        val tab = current()
        val url = tab?.web?.url ?: ""
        when (id) {
            R.id.menu_new_tab -> createTab(HOME)
            R.id.menu_new_incognito -> {
                createTab(HOME, incognito = true)
                Toast.makeText(this, "시크릿 탭이 열립니다 (기록 저장 안 함)", Toast.LENGTH_SHORT).show()
            }
            R.id.menu_close_tab -> closeTab(current)
            R.id.menu_add_bookmark -> {
                if (url.isNotEmpty()) {
                    BookmarkRepo.add(this, tab?.web?.title ?: url, url)
                    Toast.makeText(this, "즐겨찾기에 추가되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            R.id.menu_bookmarks ->
                startActivityForResult(Intent(this, com.example.streambrowser.ui.BookmarksActivity::class.java), 1)
            R.id.menu_history ->
                startActivityForResult(Intent(this, com.example.streambrowser.ui.HistoryActivity::class.java), 2)
            R.id.menu_downloads ->
                startActivity(Intent(this, com.example.streambrowser.ui.DownloadsActivity::class.java))
            R.id.menu_find -> {
                findBar.visibility = View.VISIBLE
                findInput.requestFocus()
            }
            R.id.menu_desktop -> {
                val on = !prefs.getBoolean("desktop", false)
                prefs.edit().putBoolean("desktop", on).apply()
                tabs.forEach {
                    it.web.settings.userAgentString = if (on) UA_DESKTOP else UA_MOBILE
                    it.web.reload()
                }
            }
            R.id.menu_dark -> {
                val on = !prefs.getBoolean("dark", false)
                prefs.edit().putBoolean("dark", on).apply()
                tabs.forEach { applyDarkMode(it.web.settings) }
                Toast.makeText(this, "어두운 모드 ${if (on) "켜짐" else "꺼짐"}", Toast.LENGTH_SHORT).show()
            }
            R.id.menu_adblock -> {
                AdBlocker.enabled = !AdBlocker.enabled
                Toast.makeText(this, "광고 차단 ${if (AdBlocker.enabled) "켜짐" else "꺼짐"}", Toast.LENGTH_SHORT).show()
            }
            R.id.menu_restore_tabs -> {
                val on = !prefs.getBoolean("restore_tabs", true)
                prefs.edit().putBoolean("restore_tabs", on).apply()
                Toast.makeText(this, "시작 시 탭 복원 ${if (on) "켜짐" else "꺼짐"}", Toast.LENGTH_SHORT).show()
            }
            R.id.menu_text_size -> showTextSizeDialog()
            R.id.menu_capture -> captureCurrentPage()
            R.id.menu_share -> {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                }
                startActivity(Intent.createChooser(i, "공유"))
            }
            R.id.menu_copy_url -> {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("url", url))
                Toast.makeText(this, "URL이 복사되었습니다.", Toast.LENGTH_SHORT).show()
            }
            R.id.menu_add_adblock_rule -> addAdBlockRule(url)
            R.id.menu_clear_data -> confirmClearData()
            R.id.menu_about -> showAbout()
            R.id.menu_exit -> finish()
        }
    }

    private fun applyDarkMode(settings: WebSettings) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                settings.forceDark =
                    if (prefs.getBoolean("dark", false)) WebSettings.FORCE_DARK_ON
                    else WebSettings.FORCE_DARK_OFF
            }
        }
    }

    private fun showTextSizeDialog() {
        val sizes = arrayOf("50%", "75%", "100%", "125%", "150%")
        val values = arrayOf(50, 75, 100, 125, 150)
        val cur = values.indexOf(prefs.getInt("text_zoom", 100)).let { if (it < 0) 2 else it }
        AlertDialog.Builder(this)
            .setTitle("글자 크기")
            .setSingleChoiceItems(sizes, cur) { d, which ->
                prefs.edit().putInt("text_zoom", values[which]).apply()
                tabs.forEach { it.web.settings.textZoom = values[which] }
                d.dismiss()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun captureCurrentPage() {
        val wv = current()?.web ?: return
        runCatching {
            val bmp = Bitmap.createBitmap(wv.width, wv.contentHeight.coerceAtLeast(wv.height), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            wv.draw(canvas)
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "JC Browser").apply { mkdirs() }
            val f = File(dir, "capture_${System.currentTimeMillis()}.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Toast.makeText(this, "캡처 저장: ${f.absolutePath}", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, "캡처 실패", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addAdBlockRule(url: String) {
        val host = runCatching { Uri.parse(url).host }.getOrNull()
        if (host.isNullOrEmpty()) {
            Toast.makeText(this, "유효한 페이지가 아닙니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply { setText(host) }
        AlertDialog.Builder(this)
            .setTitle("광고 차단할 도메인")
            .setView(input)
            .setPositiveButton("추가") { _, _ ->
                AdBlocker.addCustomHost(this, input.text.toString().trim())
                current()?.web?.reload()
                Toast.makeText(this, "차단 목록에 추가되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle("방문 기록/캐시 삭제")
            .setMessage("방문 기록, 쿠키, 캐시를 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                HistoryRepo.clear(this)
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies(null)
                tabs.forEach { it.web.clearCache(true) }
                Toast.makeText(this, "삭제되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("JC Browser")
            .setMessage(
                "버전 1.7.0\n\n" +
                        "· 광고/트래커 차단\n" +
                        "· 스트리밍 영상 감지 및 다운로드 (HLS/DASH)\n" +
                        "· 이미지 감지 및 다운로드\n" +
                        "· 멀티 탭 / 시크릿 탭\n" +
                        "· 즐겨찾기(폴터/가져오기·낳볶기), 방문 기록, 다운로드 관리\n\n" +
                        "WebView 엔진 기반 브라우저"
            )
            .setPositiveButton("확인", null)
            .show()
    }

    // ------------------------------------------------------- 기타

    private fun loadUrl() {
        var u = editUrl.text.toString().trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = if (u.contains(".") && !u.contains(" ")) "https://$u" else "https://www.google.com/search?q=$u"
        }
        current()?.web?.loadUrl(u)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == 1 || requestCode == 2) && resultCode == RESULT_OK) {
            data?.getStringExtra("url")?.let { current()?.web?.loadUrl(it) }
        }
    }

    /** 현재 탭 제목 (다운로드 기본 파일명 등에 사용) */
    fun currentTabTitle(): String =
        (current()?.web?.title ?: current()?.web?.url ?: "").ifBlank { "video" }

    override fun onPause() {
        super.onPause()
        // 시작 시 탭 복원용 저장 (시크릿 탭 제외)
        if (prefs.getBoolean("restore_tabs", true)) {
            val urls = tabs.filter { !it.incognito }.mapNotNull { it.web.url }.filter { it.isNotBlank() }
            prefs.edit()
                .putString("saved_tabs", urls.joinToString("\n"))
                .putInt("saved_index", current.coerceIn(0, (urls.size - 1).coerceAtLeast(0)))
                .apply()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullscreenView != null) {
            chromeClient.onHideCustomView()
            return
        }
        if (findBar.visibility == View.VISIBLE) {
            findBar.visibility = View.GONE
            current()?.web?.clearMatches()
            return
        }
        if (videoList.visibility == View.VISIBLE) {
            videoList.visibility = View.GONE
            return
        }
        val wv = current()?.web
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return
        }
        // 링크로 열린 새 탭에서는 뒤로가기 = 탭 닫고 이전 탭으로 복귀 (크롬 동작)
        if (current()?.fromWindow == true && tabs.size > 1) {
            val backTo = (current - 1).coerceAtLeast(0)
            tabs[current].web.destroy()
            tabs.removeAt(current)
            current = backTo.coerceAtMost(tabs.size - 1)
            showCurrent()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        VideoStore.listener = null
        tabs.forEach { it.web.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}
