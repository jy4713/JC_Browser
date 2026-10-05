package com.example.streambrowser

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Rational
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
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.streambrowser.browser.AdBlocker
import com.example.streambrowser.browser.SniffingWebViewClient
import com.example.streambrowser.browser.TabMedia
import com.example.streambrowser.browser.VideoJsBridge
import com.example.streambrowser.browser.VideoStore
import com.example.streambrowser.db.BookmarkRepo
import com.example.streambrowser.db.HistoryRepo
import com.example.streambrowser.download.ImageAdapter
import com.example.streambrowser.download.ImageDownloader
import com.example.streambrowser.download.ThumbLoader
import com.example.streambrowser.download.VideoAdapter
import com.example.streambrowser.util.LocaleHelper
import java.io.File

class MainActivity : Activity() {

    private data class Tab(val web: WebView, var incognito: Boolean = false, var fromWindow: Boolean = false)

    private val tabs = mutableListOf<Tab>()
    private var current = 0

    private lateinit var container: FrameLayout
    private lateinit var editUrl: EditText
    private lateinit var btnVideos: ImageButton
    private lateinit var btnImages: ImageButton
    private lateinit var btnNavTabs: ImageButton
    private lateinit var btnNavBack: ImageButton
    private lateinit var btnNavForward: ImageButton
    private lateinit var badgeVideos: TextView
    private lateinit var badgeImages: TextView
    private lateinit var badgeTabs: TextView
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var videoList: RecyclerView
    private lateinit var imageGrid: RecyclerView
    private lateinit var mediaPanel: LinearLayout
    private lateinit var txtMediaHeader: TextView
    private lateinit var txtToggleBlocked: TextView
    private lateinit var btnSelectAll: TextView
    private lateinit var btnDlSelected: Button
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var chromeClient: WebChromeClient
    private lateinit var prefs: SharedPreferences

    private lateinit var videoAdapter: VideoAdapter
    private lateinit var imageAdapter: ImageAdapter

    /** 네이티브 전체화면(커스텀 뷰) / JS 강제 전체화면 상태 */
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var jsFsActive = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val HOME = "https://www.google.com"
    private val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        AdBlocker.init(this)

        container = findViewById(R.id.webContainer)
        editUrl = findViewById(R.id.editUrl)
        btnVideos = findViewById(R.id.btnVideos)
        btnImages = findViewById(R.id.btnImages)
        btnNavTabs = findViewById(R.id.btnNavTabs)
        badgeVideos = findViewById(R.id.badgeVideos)
        badgeImages = findViewById(R.id.badgeImages)
        badgeTabs = findViewById(R.id.badgeTabs)
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        videoList = findViewById(R.id.videoList)
        imageGrid = findViewById(R.id.imageGrid)
        mediaPanel = findViewById(R.id.mediaPanel)
        txtMediaHeader = findViewById(R.id.txtMediaHeader)
        txtToggleBlocked = findViewById(R.id.txtToggleBlocked)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnDlSelected = findViewById(R.id.btnDlSelected)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)

        videoAdapter = VideoAdapter()
        videoAdapter.provider = { currentMedia() }
        videoList.layoutManager = LinearLayoutManager(this)
        videoList.adapter = videoAdapter

        imageAdapter = ImageAdapter { count ->
            btnDlSelected.text = getString(R.string.dl_selected, count)
        }
        imageAdapter.provider = { currentMedia() }
        imageGrid.layoutManager = GridLayoutManager(this, 3)
        imageGrid.adapter = imageAdapter

        // 주소창
        editUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { loadUrl(); true } else false
        }

        // Chrome 제스처: 주소창 좌우 스와이프로 탭 전환
        val tabFling = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (Math.abs(dx) > 120 && Math.abs(vx) > 250 && Math.abs(dx) > Math.abs(dy) * 1.5) {
                    if (dx < 0 && current < tabs.size - 1) { current++; showCurrent(); return true }
                    if (dx > 0 && current > 0) { current--; showCurrent(); return true }
                }
                return false
            }
        })
        editUrl.setOnTouchListener { _, ev -> tabFling.onTouchEvent(ev); false }

        // 미디어 버튼: 동영상 / 이미지 패널
        btnVideos.setOnClickListener { toggleVideoPanel() }
        btnImages.setOnClickListener { toggleImagePanel() }
        txtToggleBlocked.setOnClickListener {
            videoAdapter.showBlocked = !videoAdapter.showBlocked
            updateBlockedToggle()
            videoAdapter.notifyDataSetChanged()
        }
        btnSelectAll.setOnClickListener { imageAdapter.selectAll() }
        btnDlSelected.setOnClickListener { downloadSelectedImages() }

        // 하단 툴팁
        btnNavBack = findViewById(R.id.btnNavBack)
        btnNavForward = findViewById(R.id.btnNavForward)
        btnNavBack.setOnClickListener { onBackPressed() }
        // Chrome: 뒤로가기 길게 누르기 → 이 탭의 방문 기록 팝업
        btnNavBack.setOnLongClickListener { showBackHistory() }
        btnNavForward.setOnClickListener {
            current()?.let { if (it.web.canGoForward()) it.web.goForward() }
        }
        findViewById<ImageButton>(R.id.btnNavRefresh).setOnClickListener { current()?.web?.reload() }
        findViewById<ImageButton>(R.id.btnNavHome).setOnClickListener {
            current()?.web?.loadUrl(prefs.getString("home_url", HOME) ?: HOME)
        }
        findViewById<ImageButton>(R.id.btnNavBookmark).setOnClickListener {
            val url = current()?.web?.url
            if (!url.isNullOrEmpty()) {
                BookmarkRepo.add(this, current()?.web?.title ?: url, url)
                Toast.makeText(this, getString(R.string.bookmark_added), Toast.LENGTH_SHORT).show()
            }
        }
        btnNavTabs.setOnClickListener { showTabDialog() }
        findViewById<ImageButton>(R.id.btnNavMenu).setOnClickListener { showMainMenu() }

        // 페이지 내 찾기
        findInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                current()?.web?.findAllAsync(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        findViewById<ImageButton>(R.id.btnFindNext).setOnClickListener { current()?.web?.findNext(true) }
        findViewById<ImageButton>(R.id.btnFindPrev).setOnClickListener { current()?.web?.findNext(false) }
        findViewById<ImageButton>(R.id.btnFindClose).setOnClickListener {
            current()?.web?.clearMatches()
            findBar.visibility = View.GONE
        }

        VideoStore.listener = {
            runOnUiThread {
                refreshMediaBadges()
                if (mediaPanel.visibility == View.VISIBLE) {
                    videoAdapter.notifyDataSetChanged()
                    imageAdapter.notifyDataSetChanged()
                    refreshMediaHeader()
                }
            }
        }

        // Soul 스타일 동영상 길게 누르기 메뉴 (JS 다리)
        VideoJsBridge.onVideoLongPress = { runOnUiThread { showVideoMenu() } }

        // WebView 사용 불가 기기 방어
        val webViewAvailable = runCatching {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || WebView.getCurrentWebViewPackage() != null
        }.getOrDefault(true)
        if (!webViewAvailable) {
            showWebViewErrorAndFinish()
            return
        }

        createTab(HOME) ?: run { showWebViewErrorAndFinish(); return }

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

        updatePipParams()
    }

    // ------------------------------------------------------- 미디어 패널

    private fun captureSnapshot() {
        val wv = current()?.web ?: return
        runCatching {
            val b = Bitmap.createBitmap(
                wv.width.coerceAtLeast(1), wv.height.coerceAtLeast(1), Bitmap.Config.RGB_565
            )
            wv.draw(Canvas(b))
            ThumbLoader.PageSnapshot.bitmap = b
            ThumbLoader.PageSnapshot.pageUrl = wv.url ?: ""
        }
    }

    private fun refreshMediaHeader() {
        if (videoList.visibility == View.VISIBLE) {
            txtMediaHeader.text = "${getString(R.string.cd_videos)} (${videoAdapter.itemCount})"
            updateBlockedToggle()
        } else {
            txtMediaHeader.text = "${getString(R.string.cd_images)} (${currentMedia()?.images?.size ?: 0})"
        }
    }

    private fun updateBlockedToggle() {
        val n = videoAdapter.blockedCount()
        txtToggleBlocked.visibility = if (n > 0) View.VISIBLE else View.GONE
        txtToggleBlocked.text = getString(
            if (videoAdapter.showBlocked) R.string.hide_blocked else R.string.show_blocked, n
        )
    }

    private fun toggleVideoPanel() {
        if (mediaPanel.visibility == View.VISIBLE && videoList.visibility == View.VISIBLE) {
            closePanels()
            return
        }
        captureSnapshot()
        mediaPanel.visibility = View.VISIBLE
        videoList.visibility = View.VISIBLE
        imageGrid.visibility = View.GONE
        txtToggleBlocked.visibility = View.VISIBLE
        btnSelectAll.visibility = View.GONE
        btnDlSelected.visibility = View.GONE
        refreshMediaHeader()
        videoAdapter.notifyDataSetChanged()
    }

    private fun toggleImagePanel() {
        if (mediaPanel.visibility == View.VISIBLE && imageGrid.visibility == View.VISIBLE) {
            closePanels()
            return
        }
        mediaPanel.visibility = View.VISIBLE
        videoList.visibility = View.GONE
        imageGrid.visibility = View.VISIBLE
        txtToggleBlocked.visibility = View.GONE
        btnSelectAll.visibility = View.VISIBLE
        btnDlSelected.visibility = View.VISIBLE
        btnDlSelected.text = getString(R.string.dl_selected, 0)
        refreshMediaHeader()
        imageAdapter.notifyDataSetChanged()
    }

    private fun closePanels() {
        mediaPanel.visibility = View.GONE
    }

    /** 선택한 이미지 일괄 다운로드 */
    private fun downloadSelectedImages() {
        val sel = imageAdapter.selectedItems()
        if (sel.isEmpty()) {
            Toast.makeText(this, getString(R.string.dl_selected, 0), Toast.LENGTH_SHORT).show()
            return
        }
        for ((idx, v) in sel.withIndex()) {
            val seg = v.url.substringBefore("?").substringAfterLast("/")
            val name = (if (seg.isBlank()) "image_${System.currentTimeMillis()}_$idx"
            else seg.substringBeforeLast(".")).ifBlank { "image_$idx" }
            val ext = Regex("\\.([A-Za-z0-9]{2,5})$").find(seg)?.groupValues?.get(1) ?: "jpg"
            ImageDownloader.download(this, v.url, v.page, name, ext)
        }
        Toast.makeText(this, getString(R.string.image_dl_started), Toast.LENGTH_SHORT).show()
        imageAdapter.clearSelection()
    }

    // ------------------------------------------------------- WebView 충돌 방어

    private fun showWebViewErrorAndFinish() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.webview_error_title))
            .setMessage(getString(R.string.webview_error_msg))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.btn_exit)) { _, _ -> finish() }
            .show()
    }

    // ------------------------------------------------------- 탭 관리

    private fun current(): Tab? = tabs.getOrNull(current)

    /** VideoAdapter용: 새 탭으로 URL 열기 */
    fun openInNewTab(url: String, incognito: Boolean) {
        createTab(url, incognito = incognito)
    }

    /** VideoAdapter용: 텍스트 복사 */
    fun copyTextPublic(text: String, msg: String) = copyText(text, msg)

    private fun createTab(url: String, incognito: Boolean = false): Tab? {
        val wv = try {
            WebView(this)
        } catch (t: Throwable) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { safeBrowsingEnabled = true }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true) }
        }
        wv.webViewClient = SniffingWebViewClient(
            onPageStartedCb = { view, url ->
                runOnUiThread {
                    if (view == current()?.web) {
                        editUrl.setText(url)
                        updateNavButtons()
                    }
                    // 이 탭의 페이지 이동 → 이 탭의 목록만 리셋
                    VideoStore.clear(view)
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
                    runCatching { view.evaluateJavascript(VideoJsBridge.SCANNER_JS, null) }
                    // Brave 스타일 요소 숨김 (##규칙 CSS 주입)
                    val css = AdBlocker.hideCss()
                    if (css.isNotEmpty()) {
                        runCatching {
                            val js = "var s=document.createElement('style');" +
                                    "s.textContent=${org.json.JSONObject.quote(css)};" +
                                    "document.head.appendChild(s);"
                            view.evaluateJavascript(js, null)
                        }
                    }
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
        wv.setOnLongClickListener { showHitMenu(wv) }
        val tab = Tab(wv, incognito)
        tabs.add(tab)
        current = tabs.size - 1
        runCatching { wv.addJavascriptInterface(VideoJsBridge(wv), "StreamBrowser") }
        if (url.isNotEmpty()) wv.loadUrl(url)
        showCurrent()
        return tab
    }

    private fun closeTab(index: Int) {
        if (tabs.size <= 1) {
            Toast.makeText(this, getString(R.string.last_tab), Toast.LENGTH_SHORT).show()
            return
        }
        tabs[index].web.destroy()
        tabs.removeAt(index)
        if (current >= tabs.size) current = tabs.size - 1
        showCurrent()
    }

    private fun showCurrent() {
        container.removeAllViews()
        current()?.let { container.addView(it.web) }
        editUrl.setText(current()?.web?.url ?: "")
        badgeTabs.text = tabs.size.toString()
        updateNavButtons()
        // 백그라운드 탭은 정지 → 스캐너 JS 수집/미디어 재생 중단 (크롬 방식)
        tabs.forEachIndexed { i, t ->
            runCatching { if (i == current) t.web.onResume() else t.web.onPause() }
        }
        // 탭별 목록: 전환핸도 각 탭은 자기 페이지의 동영상/이미지만 표시
        imageAdapter.clearSelection()
        refreshMediaBadges()
        if (mediaPanel.visibility == View.VISIBLE) {
            videoAdapter.notifyDataSetChanged()
            imageAdapter.notifyDataSetChanged()
            refreshMediaHeader()
        }
    }

    /** 현재 탭의 미디어 목록 */
    private fun currentMedia(): TabMedia? =
        current()?.web?.let { VideoStore.mediaFor(it) }

    private fun refreshMediaBadges() {
        val m = currentMedia()
        val nVideos = m?.videos?.size ?: 0
        val nImages = m?.images?.size ?: 0
        badgeVideos.text = nVideos.toString()
        badgeVideos.visibility = if (nVideos > 0) View.VISIBLE else View.GONE
        badgeImages.text = nImages.toString()
        badgeImages.visibility = if (nImages > 0) View.VISIBLE else View.GONE
    }

    private fun updateNavButtons() {
        val wv = current()?.web
        btnNavBack.alpha =
            if (wv != null && (wv.canGoBack() || (current()?.fromWindow == true && tabs.size > 1))) 1.0f else 0.3f
        btnNavForward.alpha = if (wv != null && wv.canGoForward()) 1.0f else 0.3f
    }

    /** 렌더 프로세스가 죽은 탭을 같은 URL로 복구 (Chrome 방식) */
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
            tabs.removeAt(tabs.size - 1)
            tabs.add(idx.coerceAtMost(tabs.size), nt)
            current = idx.coerceAtMost(tabs.size - 1)
            showCurrent()
            Toast.makeText(this, getString(R.string.render_recovered), Toast.LENGTH_SHORT).show()
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
                txtCount.text = "${getString(R.string.dlg_tabs_count)} ${tabs.size}"
                tabAdapter.submit(tabs)
            }
        )
        list.adapter = tabAdapter
        tabAdapter.submit(tabs)
        txtCount.text = "${getString(R.string.dlg_tabs_count)} ${tabs.size}"

        dlg.findViewById<ImageButton>(R.id.btnTabsClose).setOnClickListener { dlg.dismiss() }
        dlg.findViewById<Button>(R.id.btnNewTab).setOnClickListener {
            createTab(HOME)
            dlg.dismiss()
        }
        dlg.findViewById<Button>(R.id.btnCloseCurrent).setOnClickListener {
            closeTab(current)
            txtCount.text = "${getString(R.string.dlg_tabs_count)} ${tabs.size}"
            tabAdapter.submit(tabs)
        }
        dlg.show()
    }

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
            val base = t.web.title ?: t.web.url ?: "…"
            val title = if (base.length > 60) base.substring(0, 60) + "…" else base
            h.title.text = (if (t.incognito) "🔒 " else "") + title
            h.url.text = t.web.url ?: ""
            h.itemView.setBackgroundColor(
                if (position == current) 0xFFE8F0FE.toInt() else Color.TRANSPARENT
            )
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


    // ------------------------------------------------------- 메뉴 (Soul 스타일: 단축키 그리드 + 설정 그룹)

    private class MenuEntry(
        val title: String,
        val iconRes: Int,
        val prefKey: String?,     // 체크 상태 표시용 pref
        val action: () -> Unit
    )

    private class MenuGroup(
        val groupRes: Int,
        val iconRes: Int,
        val items: List<MenuEntry>
    )

    private sealed class MenuRow {
        class Shortcut : MenuRow()
        class Quick(val entry: MenuEntry) : MenuRow()
        class Header(val groupRes: Int) : MenuRow()
        class Child(val entry: MenuEntry) : MenuRow()
    }

    private var menuDialog: Dialog? = null
    private var expandedGroup: Int? = null

    /** 상단 단축키 그리드 항목 (아이콘, 라벨, 동작) */
    private fun shortcutItems(): List<Triple<Int, Int, () -> Unit>> {
        val s = fun(res: Int) = getString(res)
        return listOf(
            Triple(R.drawable.ic_tabs, R.string.menu_new_tab) {
                createTab(HOME)
                menuDialog?.dismiss()
            },
            Triple(R.drawable.ic_incognito, R.string.menu_new_incognito) {
                createTab(HOME, incognito = true)
                Toast.makeText(this, s(R.string.incognito_on), Toast.LENGTH_SHORT).show()
                menuDialog?.dismiss()
            },
            Triple(R.drawable.ic_bookmark_add, R.string.menu_add_bookmark) {
                val url = current()?.web?.url
                if (!url.isNullOrEmpty()) {
                    BookmarkRepo.add(this, current()?.web?.title ?: url, url)
                    Toast.makeText(this, s(R.string.bookmark_added), Toast.LENGTH_SHORT).show()
                }
                menuDialog?.dismiss()
            },
            Triple(R.drawable.ic_bookmark, R.string.menu_bookmarks) {
                startActivityForResult(Intent(this, com.example.streambrowser.ui.BookmarksActivity::class.java), 1)
                menuDialog?.dismiss()
            },
            Triple(R.drawable.ic_history, R.string.menu_history) {
                startActivityForResult(Intent(this, com.example.streambrowser.ui.HistoryActivity::class.java), 2)
                menuDialog?.dismiss()
            },
            Triple(R.drawable.ic_download, R.string.menu_downloads) {
                startActivity(Intent(this, com.example.streambrowser.ui.DownloadsActivity::class.java))
                menuDialog?.dismiss()
            },
            Triple(R.drawable.ic_search, R.string.menu_find) {
                findBar.visibility = View.VISIBLE
                findInput.requestFocus()
                menuDialog?.dismiss()
            },
            Triple(R.drawable.ic_image, R.string.menu_capture) {
                captureCurrentPage()
                menuDialog?.dismiss()
            }
        )
    }

    /** 설정 그룹 (아코디언): 용도별 4개 그룹으로 정리 */
    private fun menuGroups(): List<MenuGroup> {
        val s = fun(res: Int) = getString(res)
        return listOf(
            MenuGroup(R.string.group_dl_settings, R.drawable.ic_download, listOf(
                MenuEntry(s(R.string.menu_dl_folder), R.drawable.ic_folder, null) {
                    showDownloadFolderDialog()
                },
                MenuEntry(s(R.string.menu_fast_dl), R.drawable.ic_play, "fast_dl") {
                    val on = !prefs.getBoolean("fast_dl", true)
                    prefs.edit().putBoolean("fast_dl", on).apply()
                },
                MenuEntry(getString(R.string.menu_dl_split, prefs.getInt("dl_split", 8)), R.drawable.ic_folder, null) {
                    showSplitDialog()
                },
                MenuEntry(getString(R.string.menu_dl_conn, prefs.getInt("dl_conn", 4)), R.drawable.ic_folder, null) {
                    showConnDialog()
                },
                MenuEntry(s(R.string.menu_dl_notify), R.drawable.ic_play, "dl_notify") {
                    val on = !prefs.getBoolean("dl_notify", true)
                    prefs.edit().putBoolean("dl_notify", on).apply()
                }
            )),
            MenuGroup(R.string.group_display, R.drawable.ic_search, listOf(
                MenuEntry(s(R.string.menu_desktop), R.drawable.ic_refresh, "desktop") {
                    val on = !prefs.getBoolean("desktop", false)
                    prefs.edit().putBoolean("desktop", on).apply()
                    tabs.forEach {
                        it.web.settings.userAgentString = if (on) UA_DESKTOP else UA_MOBILE
                        it.web.reload()
                    }
                },
                MenuEntry(s(R.string.menu_dark), R.drawable.ic_menu_vert, "dark") {
                    val on = !prefs.getBoolean("dark", false)
                    prefs.edit().putBoolean("dark", on).apply()
                    tabs.forEach { applyDarkMode(it.web.settings) }
                    Toast.makeText(this, s(if (on) R.string.dark_on else R.string.dark_off), Toast.LENGTH_SHORT).show()
                },
                MenuEntry(s(R.string.menu_text_size), R.drawable.ic_expand_more, null) {
                    showTextSizeDialog()
                },
                MenuEntry(s(R.string.menu_auto_pip), R.drawable.ic_play, "auto_pip") {
                    val on = !prefs.getBoolean("auto_pip", false)
                    prefs.edit().putBoolean("auto_pip", on).apply()
                    updatePipParams()
                    var msg = s(if (on) R.string.auto_pip_on else R.string.auto_pip_off)
                    if (on && Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.R)
                        msg += s(R.string.auto_pip_legacy)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            )),
            MenuGroup(R.string.group_privacy, R.drawable.ic_incognito, listOf(
                MenuEntry(s(R.string.menu_adblock), R.drawable.ic_close, "adblock") {
                    AdBlocker.enabled = !AdBlocker.enabled
                    Toast.makeText(this, s(if (AdBlocker.enabled) R.string.adblock_on else R.string.adblock_off), Toast.LENGTH_SHORT).show()
                },
                MenuEntry(s(R.string.menu_add_adblock_rule), R.drawable.ic_close, null) {
                    current()?.web?.url?.let { addAdBlockRule(it) }
                },
                MenuEntry(s(R.string.menu_clear_data), R.drawable.ic_close, null) {
                    confirmClearData()
                }
            )),
            MenuGroup(R.string.group_general, R.drawable.ic_settings, listOf(
                MenuEntry(s(R.string.menu_home_setting), R.drawable.ic_home, null) {
                    showHomeDialog()
                },
                MenuEntry(s(R.string.menu_restore_tabs), R.drawable.ic_tabs, "restore_tabs") {
                    val on = !prefs.getBoolean("restore_tabs", true)
                    prefs.edit().putBoolean("restore_tabs", on).apply()
                    Toast.makeText(this, s(if (on) R.string.restore_on else R.string.restore_off), Toast.LENGTH_SHORT).show()
                },
                MenuEntry(s(R.string.menu_import_bookmarks), R.drawable.ic_bookmark_add, null) {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    runCatching { startActivityForResult(i, 3) }
                        .onFailure { Toast.makeText(this, s(R.string.bookmark_import_failed), Toast.LENGTH_SHORT).show() }
                },
                MenuEntry(s(R.string.menu_export_bookmarks), R.drawable.ic_bookmark_add, null) {
                    val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/html"
                        putExtra(Intent.EXTRA_TITLE, "jc_browser_bookmarks.html")
                    }
                    runCatching { startActivityForResult(i, 4) }
                        .onFailure { Toast.makeText(this, s(R.string.bookmark_export_failed), Toast.LENGTH_SHORT).show() }
                },
                MenuEntry(s(R.string.menu_language), R.drawable.ic_menu_vert, null) {
                    showLanguageDialog()
                },
                MenuEntry(s(R.string.menu_about), R.drawable.ic_search, null) {
                    showAbout()
                },
                MenuEntry(s(R.string.menu_exit), R.drawable.ic_close, null) {
                    finish()
                }
            ))
        )
    }
    private fun buildMenuRows(): List<MenuRow> {
        val rows = mutableListOf<MenuRow>()
        rows += MenuRow.Shortcut()
        rows += MenuRow.Quick(MenuEntry(getString(R.string.menu_pip), R.drawable.ic_play, null) {
            enterPipManual()
            menuDialog?.dismiss()
        })
        rows += MenuRow.Quick(MenuEntry(getString(R.string.menu_close_tab), R.drawable.ic_close, null) {
            closeTab(current)
            menuDialog?.dismiss()
        })
        for (g in menuGroups()) {
            rows += MenuRow.Header(g.groupRes)
            if (expandedGroup == g.groupRes) {
                g.items.forEach { rows += MenuRow.Child(it) }
            }
        }
        return rows
    }

    private fun showMainMenu() {
        val dlg = Dialog(this)
        menuDialog = dlg
        dlg.setContentView(R.layout.dialog_menu)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.setOnDismissListener { menuDialog = null }
        refreshMenuContent(dlg)
        dlg.show()
    }

    private fun rebuildMenu() {
        menuDialog?.let { refreshMenuContent(it) }
    }

    private fun refreshMenuContent(dlg: Dialog) {
        val list = dlg.findViewById<RecyclerView>(R.id.listMenu) ?: return
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = MenuSheetAdapter(buildMenuRows())
    }

    private fun showSplitDialog() {
        val values = intArrayOf(2, 4, 8, 16)
        val labels = values.map { it.toString() }.toTypedArray()
        val cur = values.indexOf(prefs.getInt("dl_split", 8)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_split_title))
            .setSingleChoiceItems(labels, cur) { d, which ->
                prefs.edit().putInt("dl_split", values[which]).apply()
                d.dismiss()
                rebuildMenu()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showConnDialog() {
        val values = intArrayOf(1, 2, 4, 6, 8)
        val labels = values.map { it.toString() }.toTypedArray()
        val cur = values.indexOf(prefs.getInt("dl_conn", 4)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_conn_title))
            .setSingleChoiceItems(labels, cur) { d, which ->
                prefs.edit().putInt("dl_conn", values[which]).apply()
                d.dismiss()
                rebuildMenu()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showHomeDialog() {
        val edit = EditText(this).apply {
            setText(prefs.getString("home_url", HOME) ?: HOME)
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_home_title))
            .setView(edit)
            .setPositiveButton(getString(R.string.btn_ok)) { _, _ ->
                var u = edit.text.toString().trim()
                if (u.isNotEmpty()) {
                    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
                    prefs.edit().putString("home_url", u).apply()
                    Toast.makeText(this, getString(R.string.menu_home_saved), Toast.LENGTH_SHORT).show()
                    rebuildMenu()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private inner class MenuSheetAdapter(
        private val rows: List<MenuRow>
    ) : RecyclerView.Adapter<MenuSheetAdapter.VH>() {

        inner class VH(v: View, val type: Int) : RecyclerView.ViewHolder(v) {
            val group: TextView? = v.findViewById(R.id.menuGroupTitle)
            val icon: ImageButton? = v.findViewById(R.id.menuIcon)
            val title: TextView? = v.findViewById(R.id.menuTitle)
            val state: TextView? = v.findViewById(R.id.menuState)
        }

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is MenuRow.Shortcut) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = if (viewType == 0)
                LayoutInflater.from(parent.context).inflate(R.layout.item_menu_shortcuts, parent, false)
            else
                LayoutInflater.from(parent.context).inflate(R.layout.item_menu, parent, false)
            return VH(v, viewType)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            when (val r = rows[position]) {
                is MenuRow.Shortcut -> {
                    val grid = h.itemView.findViewById<GridLayout>(R.id.shortcutGrid) ?: return
                    grid.removeAllViews()
                    val inflater = LayoutInflater.from(this@MainActivity)
                    shortcutItems().forEach { (ic, label, act) ->
                        val cell = inflater.inflate(R.layout.item_shortcut_cell, grid, false)
                        cell.findViewById<ImageView>(R.id.scIcon).setImageResource(ic)
                        cell.findViewById<TextView>(R.id.scLabel).text = getString(label)
                        cell.setOnClickListener { act() }
                        grid.addView(cell)
                    }
                }
                is MenuRow.Header -> {
                    h.group?.visibility = View.GONE
                    h.icon?.setImageResource(
                        menuGroups().firstOrNull { it.groupRes == r.groupRes }?.iconRes ?: R.drawable.ic_menu_vert
                    )
                    h.title?.text = getString(r.groupRes)
                    h.title?.setTypeface(h.title?.typeface, android.graphics.Typeface.BOLD)
                    h.state?.visibility = View.VISIBLE
                    h.state?.text = if (expandedGroup == r.groupRes) "▾" else "▸"
                    h.state?.setTextColor(Color.parseColor("#9AA0A6"))
                    h.itemView.setOnClickListener {
                        expandedGroup = if (expandedGroup == r.groupRes) null else r.groupRes
                        rebuildMenu()
                    }
                }
                is MenuRow.Quick, is MenuRow.Child -> {
                    h.group?.visibility = View.GONE
                    val e = (r as? MenuRow.Quick)?.entry ?: (r as MenuRow.Child).entry
                    h.icon?.setImageResource(e.iconRes)
                    h.title?.text = e.title
                    h.title?.setTypeface(h.title?.typeface, android.graphics.Typeface.NORMAL)
                    val checked = when (e.prefKey) {
                        null -> false
                        "adblock" -> AdBlocker.enabled
                        else -> prefs.getBoolean(e.prefKey, e.prefKey != "dl_notify")
                    }
                    h.state?.visibility = if (e.prefKey != null && checked) View.VISIBLE else View.GONE
                    h.state?.text = getString(R.string.on_state)
                    h.state?.setTextColor(Color.parseColor("#1A73E8"))
                    h.itemView.setOnClickListener {
                        e.action()
                        rebuildMenu()
                    }
                }
            }
        }
    }

    private fun showLanguageDialog() {
        val values = arrayOf("system", "ko", "en")
        val labels = arrayOf(
            getString(R.string.lang_system),
            getString(R.string.lang_ko),
            getString(R.string.lang_en)
        )
        val cur = values.indexOf(prefs.getString("lang", "system")).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_language))
            .setSingleChoiceItems(labels, cur) { d, which ->
                prefs.edit().putString("lang", values[which]).apply()
                d.dismiss()
                recreate()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showDownloadFolderDialog() {
        val values = arrayOf("public", "app")
        val labels = arrayOf(
            getString(R.string.folder_public),
            getString(R.string.folder_app)
        )
        val cur = values.indexOf(prefs.getString("dl_folder", "public")).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_dl_folder))
            .setSingleChoiceItems(labels, cur) { d, which ->
                prefs.edit().putString("dl_folder", values[which]).apply()
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ------------------------------------------------------- Soul 스타일 동영상 메뉴

    private var videoMenuPopup: PopupWindow? = null
    private var videoHideRunnable: Runnable? = null

    private fun runVideoJs(js: String) {
        runCatching { current()?.web?.evaluateJavascript(js, null) }
    }

    private fun showVideoMenu() {
        videoMenuPopup?.dismiss()
        val ctx = this
        val row = { txt: String, onClick: () -> Unit ->
            TextView(ctx).apply {
                text = txt
                textSize = 15f
                setTextColor(Color.parseColor("#202124"))
                val padV = (14 * resources.displayMetrics.density).toInt()
                val padH = (24 * resources.displayMetrics.density).toInt()
                setPadding(padH, padV, padH, padV)
                setBackgroundColor(Color.WHITE)
                setOnClickListener {
                    onClick()
                    scheduleVideoMenuHide()
                }
            }
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val r = (12 * resources.displayMetrics.density).toInt()
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = r.toFloat()
            }
            elevation = 24f
            addView(row(getString(if (jsFsActive) R.string.vm_fs_off else R.string.vm_fs_on)) {
                if (jsFsActive) {
                    runVideoJs("window.__sbFsOff();")
                    jsFsActive = false
                    topBar.visibility = View.VISIBLE
                    bottomBar.visibility = View.VISIBLE
                } else {
                    runVideoJs("window.__sbFsOn();")
                    jsFsActive = true
                    topBar.visibility = View.GONE
                    bottomBar.visibility = View.GONE
                }
            })
            addView(row(getString(R.string.vm_landscape)) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            })
            addView(row(getString(R.string.vm_portrait)) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            })
            addView(row(getString(R.string.vm_auto_rotate)) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            })
            addView(row(getString(R.string.vm_play)) {
                runVideoJs("var v=window.__sbBigVideo(); if(v){try{v.play();}catch(e){}}")
            })
            addView(row(getString(R.string.vm_pause)) {
                runVideoJs("var v=window.__sbBigVideo(); if(v){try{v.pause();}catch(e){}}")
            })
            addView(row(getString(R.string.vm_speed_1)) {
                runVideoJs("var v=window.__sbBigVideo(); if(v){v.playbackRate=1.0;}")
            })
            addView(row(getString(R.string.vm_speed_15)) {
                runVideoJs("var v=window.__sbBigVideo(); if(v){v.playbackRate=1.5;}")
            })
            addView(row(getString(R.string.vm_speed_2)) {
                runVideoJs("var v=window.__sbBigVideo(); if(v){v.playbackRate=2.0;}")
            })
            addView(row(getString(R.string.btn_cancel)) {
                videoMenuPopup?.dismiss()
            })
        }

        val pw = PopupWindow(
            box,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        pw.showAtLocation(container, Gravity.CENTER, 0, 0)
        videoMenuPopup = pw
        scheduleVideoMenuHide()
    }

    /** Soul 방식: 몇 초 무응답이면 메뉴 자동 닫힘 */
    private fun scheduleVideoMenuHide() {
        videoHideRunnable?.let { mainHandler.removeCallbacks(it) }
        videoHideRunnable = Runnable { videoMenuPopup?.dismiss() }
        mainHandler.postDelayed(videoHideRunnable!!, 4000)
    }

    // ------------------------------------------------------- 기타 헬퍼

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
            .setTitle(getString(R.string.dlg_text_size))
            .setSingleChoiceItems(sizes, cur) { d, which ->
                prefs.edit().putInt("text_zoom", values[which]).apply()
                tabs.forEach { it.web.settings.textZoom = values[which] }
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
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
            Toast.makeText(this, getString(R.string.capture_saved, f.absolutePath), Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, getString(R.string.capture_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun addAdBlockRule(url: String) {
        val host = runCatching { Uri.parse(url).host }.getOrNull()
        if (host.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.invalid_page), Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply { setText(host) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_adblock_host))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_add)) { _, _ ->
                AdBlocker.addCustomHost(this, input.text.toString().trim())
                current()?.web?.reload()
                Toast.makeText(this, getString(R.string.adblock_added), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_clear_title))
            .setMessage(getString(R.string.dlg_clear_msg))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                HistoryRepo.clear(this)
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies(null)
                tabs.forEach { it.web.clearCache(true) }
                Toast.makeText(this, getString(R.string.clear_done), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(
                getString(R.string.about_version, "2.0.0") + "\n\n" +
                        "· Ad & tracker blocking (Brave-style EasyList)\n" +
                        "· Streaming video download (HLS/DASH) + images\n" +
                        "· Multi tab / incognito / PIP\n" +
                        "· Bookmarks, history, downloads\n\n" +
                        "WebView based browser"
            )
            .setPositiveButton(getString(R.string.btn_ok), null)
            .show()
    }

    // ------------------------------------------------------- Chrome/Brave 롱프레스 메뉴

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
        val opts = arrayOf(
            getString(R.string.item_open_tab),
            getString(R.string.item_open_incognito),
            getString(R.string.item_copy_link),
            getString(R.string.item_share)
        )
        AlertDialog.Builder(this)
            .setTitle(url.let { if (it.length > 60) it.take(60) + "…" else it })
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> createTab(url)
                    1 -> createTab(url, incognito = true)
                    2 -> copyText(url, getString(R.string.link_copied))
                    3 -> {
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                        }
                        startActivity(Intent.createChooser(i, null))
                    }
                }
            }
            .show()
    }

    private fun showImageMenu(imgUrl: String, page: String) {
        val opts = arrayOf(
            getString(R.string.item_open_tab),
            getString(R.string.item_download),
            getString(R.string.item_copy_link)
        )
        AlertDialog.Builder(this)
            .setTitle("…")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> createTab(imgUrl)
                    1 -> {
                        val seg = imgUrl.substringBefore("?").substringAfterLast("/")
                        val name = seg.substringBeforeLast(".").ifBlank { "image_" + System.currentTimeMillis() }
                        val ext = Regex("\\.([A-Za-z0-9]{2,5})$").find(seg)?.groupValues?.get(1) ?: "jpg"
                        ImageDownloader.download(this, imgUrl, page, name, ext)
                        Toast.makeText(this, getString(R.string.image_dl_started), Toast.LENGTH_SHORT).show()
                    }
                    2 -> copyText(imgUrl, getString(R.string.image_copied))
                }
            }
            .show()
    }

    private fun copyText(text: String, msg: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showBackHistory(): Boolean {
        val wv = current()?.web ?: return false
        val stack = runCatching { wv.copyBackForwardList() }.getOrNull() ?: return false
        if (stack.size == 0) {
            Toast.makeText(this, getString(R.string.history_empty), Toast.LENGTH_SHORT).show()
            return true
        }
        val cur = stack.currentIndex
        val items = (0 until stack.size).map { i ->
            val e = stack.getItemAtIndex(i)
            val t = (e.title ?: e.url ?: "").let { if (it.length > 50) it.take(50) + "…" else it }
            (if (i == cur) "● " else "") + t
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_history_stack))
            .setItems(items) { _, which -> wv.goBackOrForward(which - cur) }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
        return true
    }

    // ------------------------------------------------------- PIP

    private fun enterPipManual() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, getString(R.string.pip_unsupported), Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }.onFailure {
            Toast.makeText(this, getString(R.string.pip_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setAutoEnterEnabled(prefs.getBoolean("auto_pip", false))
                    .build()
                setPictureInPictureParams(params)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.R &&
            prefs.getBoolean("auto_pip", false)
        ) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (fullscreenView != null) return
        topBar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        bottomBar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
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
        // 즐겨찾기 HTML 가져오기
        if (requestCode == 3 && resultCode == RESULT_OK) {
            runCatching {
                data?.data?.let { uri ->
                    contentResolver.openInputStream(uri)?.use { input ->
                        val html = input.readBytes().toString(Charsets.UTF_8)
                        val n = BookmarkRepo.importHtml(this, html)
                        Toast.makeText(this, getString(R.string.bookmark_imported, n), Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure {
                Toast.makeText(this, getString(R.string.bookmark_import_failed), Toast.LENGTH_SHORT).show()
            }
        }
        // 즐겨찾기 HTML 내보내기
        if (requestCode == 4 && resultCode == RESULT_OK) {
            runCatching {
                data?.data?.let { uri ->
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(BookmarkRepo.exportHtml(this).toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(this, getString(R.string.bookmark_exported), Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(this, getString(R.string.bookmark_export_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 현재 탭 제목 (다운로드 기본 파일명 등에 사용) */
    fun currentTabTitle(): String =
        (current()?.web?.title ?: current()?.web?.url ?: "").ifBlank { "video" }

    override fun onPause() {
        super.onPause()
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
        // JS 강제 전체화면 상태면 해제
        if (jsFsActive) {
            runVideoJs("window.__sbFsOff();")
            jsFsActive = false
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            return
        }
        if (findBar.visibility == View.VISIBLE) {
            findBar.visibility = View.GONE
            current()?.web?.clearMatches()
            return
        }
        if (mediaPanel.visibility == View.VISIBLE) {
            closePanels()
            return
        }
        val wv = current()?.web
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return
        }
        // 링크로 열린 새 탭에서 뒤로가기 = 탭 닫고 이전 탭으로 복귀 (크롬 동작)
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
        VideoJsBridge.onVideoLongPress = null
        tabs.forEach { it.web.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}
