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
import android.content.pm.PackageManager
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
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
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
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
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
import com.example.streambrowser.browser.WebCleaner
import com.example.streambrowser.db.BookmarkRepo
import com.example.streambrowser.db.HistoryRepo
import com.example.streambrowser.download.DownloadStore
import com.example.streambrowser.download.DlStatus
import com.example.streambrowser.download.ImageAdapter
import com.example.streambrowser.download.ImageDownloader
import com.example.streambrowser.download.ThumbLoader
import com.example.streambrowser.download.VideoAdapter
import com.example.streambrowser.util.LocaleHelper
import com.example.streambrowser.vpn.JcVpnService
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

    /** 사이트 파일 업로드(input type=file) 콜백 대기 — REQ_FILE_PICK(8) 결과 수신 */
    private var pendingFileCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private val REQ_FILE_PICK = 8

    /** 백업/복원 요청 코드 — 9=저장(클라우드), 10=열기 */
    private val REQ_BACKUP_SAVE = 9
    private val REQ_BACKUP_OPEN = 10
    private var pendingBackupBytes: ByteArray? = null

    private lateinit var videoAdapter: VideoAdapter
    private lateinit var imageAdapter: ImageAdapter

    /** 네이티브 전체화면(커스텀 뷰) / JS 강제 전체화면 상태 */
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var jsFsActive = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 현재 탭에서 동영상이 재생 중인지 (JS 브리지가 갱신) — PIP 진입 조건에 사용 */
    @Volatile
    private var videoPlaying = false

    private val HOME = "https://www.google.com"
    private val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // 프린트용: attach 시 래핑 전 원본 base context 저장 (outerContext = Activity).
    // 언어/테마 래퍼(createConfigurationContext 결과)가 base 가 되면 PrintManager 의
    // mContext 가 Activity 가 아니게 되어 "Can print only from an activity" 예외 발생
    private var baseBeforeWrap: Context? = null

    override fun attachBaseContext(newBase: Context) {
        baseBeforeWrap = newBase
        super.attachBaseContext(LocaleHelper.wrap(com.example.streambrowser.util.ThemeHelper.wrap(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.streambrowser.util.ThemeHelper.apply(this)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        VideoJsBridge.imageMinWidth = prefs.getInt("img_min_px", 300)
        AdBlocker.init(this)
        WebCleaner.init(this)

        // 토렌트 링크(magnet/.torrent) 처리: ON이면 토렌트 다운로드 화면, OFF면 일반 파일 다운로드
        com.example.streambrowser.browser.SniffingWebViewClient.onTorrentLink = { _, url ->
            runOnUiThread {
                handleTorrentLink(url)
                // 가로챈 링크가 빈 팝업 탭(fromWindow)에서 열린 경우 그 탭을 닫고 원래 탭으로
                val t = current()
                if (t?.fromWindow == true && tabs.size > 1 && t.web.url.isNullOrEmpty()) {
                    closeTab(current)
                }
            }
        }

        // 토렌트 완료/실패 콜백 — 완료 시 설정된 다운로드 폴더로 납품, 시딩 중지 옵션이면 업로드 방지
        com.example.streambrowser.torrent.TorrentManager.onJobDone = { job ->
            runOnUiThread {
                com.example.streambrowser.util.JcToast.show(
                    this, getString(R.string.torrent_done, job.files().size)
                )
            }
            exportTorrentJob(job)
            if (prefs.getBoolean("torrent_no_seed", false)) {
                com.example.streambrowser.torrent.TorrentManager.stopSeeding(job.key)
            }
        }
        com.example.streambrowser.torrent.TorrentManager.onJobFailed = { job ->
            runOnUiThread {
                com.example.streambrowser.util.JcToast.show(
                    this, getString(R.string.tdl_status_failed, job.error ?: "")
                )
            }
        }
        // 저장된 토렌트 속도 제한 적용 (세션 생성 전이면 보관했다가 적용)
        com.example.streambrowser.torrent.TorrentManager.applyRateLimits(
            prefs.getInt("torrent_rate_dl", 0), prefs.getInt("torrent_rate_ul", 0)
        )

        // Android 13+ : 다운로드 진행 알림을 위한 알림 권한 요청
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
                }
            }
        }

        container = findViewById(R.id.webContainer)
        // 화면 좌/우 가장자리 스와이프 = 뒤로/앞으로
        attachEdgeSwipe(container)
        // 주소창 왼쪽 자물쇠 아이콘 = 이 사이트 설정
        findViewById<ImageButton>(R.id.btnSiteInfo).setOnClickListener { showSiteSettingsDialog() }
        // 아래로 당겨서 새로고침
        findViewById<com.example.streambrowser.ui.PullRefreshLayout>(R.id.webContainer).apply {
            indicator = findViewById(R.id.pullProgress)
            atTop = {
                fullscreenView == null &&
                    mediaPanel.visibility != View.VISIBLE &&
                    (current()?.web?.canScrollVertically(-1) == false)
            }
            onRefresh = { current()?.web?.reload() }
        }
        editUrl = findViewById(R.id.editUrl)
        // 크롬 스타일: 첫 탭에 전체 선택, 이후 탭은 커서 위치 이동 (setSelectAllOnFocus)
        editUrl.setSelectAllOnFocus(true)
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
        findViewById<ImageButton>(R.id.btnClosePanel).setOnClickListener { closePanels() }
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

        // 크롬/엣지 스타일 검색어 자동완성 제안 (DuckDuckGo suggest API)
        editUrl.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                mainHandler.removeCallbacks(suggestRunnable)
                suggestPopup?.dismiss()
                val q = s?.toString()?.trim() ?: ""
                // URL처럼 보이는 입력(도메인, 프로토콜)이면 제안 안 함
                val looksLikeUrl = q.startsWith("http") || (q.contains(".") && !q.contains(" "))
                if (q.length < 2 || looksLikeUrl || !prefs.getBoolean("suggest", true)) return
                suggestQuery = q
                mainHandler.postDelayed(suggestRunnable, 250)
            }
        })
        editUrl.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) suggestPopup?.dismiss() }

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

        // 미디어 패널 아래로 끌어내리면 닫기 (버튼 재클릭과 동일)
        val panelCloseOnDrag = View.OnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> panelDragStartY = ev.y
                MotionEvent.ACTION_MOVE -> {
                    if (panelDragStartY >= 0f && ev.y - panelDragStartY > 220 * resources.displayMetrics.density) {
                        closePanels()
                        panelDragStartY = -1f
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> panelDragStartY = -1f
            }
            false
        }
        mediaPanel.setOnTouchListener(panelCloseOnDrag)
        videoList.setOnTouchListener(panelCloseOnDrag)
        imageGrid.setOnTouchListener(panelCloseOnDrag)

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
            showAddBookmarkDialog()
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

        // 지난 다운로드 이력 로드
        DownloadStore.init(this)

        // 다운로드 진행 중이면 동영상 리스트 버튼 진행률 0.5초 간격 갱신
        mainHandler.post(object : Runnable {
            override fun run() {
                if (mediaPanel.visibility == View.VISIBLE &&
                    DownloadStore.items.any { it.status == DlStatus.PENDING || it.status == DlStatus.RUNNING }
                ) {
                    videoAdapter.notifyDataSetChanged()
                }
                mainHandler.postDelayed(this, 500)
            }
        })

        // Soul 스타일 동영상 길게 누르기 (JS 다리) — 전체화면이면 톱니 버튼 2초 표시, 아니면 바로 메뉴
        VideoJsBridge.onVideoLongPress = { runOnUiThread { onVideoLongPressed() } }

        // 동영상 재생 상태 추적 (PIP 자동 진입 여부 판단용)
        VideoJsBridge.onVideoStateChange = { playing ->
            runOnUiThread {
                videoPlaying = playing
                updatePipParams()
            }
        }

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
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.dl_selected, 0))
            return
        }
        for ((idx, v) in sel.withIndex()) {
            val seg = v.url.substringBefore("?").substringAfterLast("/")
            val name = (if (seg.isBlank()) "image_${System.currentTimeMillis()}_$idx"
            else seg.substringBeforeLast(".")).ifBlank { "image_$idx" }
            val ext = Regex("\\.([A-Za-z0-9]{2,5})$").find(seg)?.groupValues?.get(1) ?: "jpg"
            ImageDownloader.download(this, v.url, v.page, name, ext)
        }
        com.example.streambrowser.util.JcToast.show(this, getString(R.string.image_dl_started))
        imageAdapter.clearSelection()
    }

    // ------------------------------------------------------- 전체화면 몰입 모드

    /** 상태바/낵비케이션 바 숨기기 (전체화면 동영상용) */
    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    private fun exitImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
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
            // 최신 크롬/엣지와 동일 — https 페이지의 http 리소스(혼합 콘텐츠)를 차단하지 않고 허용
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = if (prefs.getBoolean("desktop", false)) UA_DESKTOP else UA_MOBILE
            textZoom = prefs.getInt("text_zoom", 100)
            blockNetworkImage = com.example.streambrowser.browser.WebCleaner.blockImages
            setSupportMultipleWindows(true)
            applyDarkMode(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { safeBrowsingEnabled = true }
            }
        }
        // 구글 "쿠키 수락" 등 서드파티 도메인(consent.google.com)의 쿠키를 허용하지 않으면
        // 동의 선택이 저장되지 않아 매번 다시 물어봄 (WebView 기본값: 서드파티 쿠키 차단)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        // 사이트별 JS 차단 초기 적용 (전역 js_block 목록 + 사이트 설정의 독립 차단 목록)
        runCatching {
            val h = Uri.parse(url).host ?: ""
            if (h.isNotEmpty() && (WebCleaner.isJsBlockedFor(h) || WebCleaner.isInSet("js_deny_hosts", h)))
                wv.settings.javaScriptEnabled = false
        }
        // Android 자동채우기 프레임워크 활성화 (삼성 패스/비밀번호 관리자가 폼 자동 입력)
        wv.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true) }
        }
        wv.webViewClient = SniffingWebViewClient(
            onPageStartedCb = { view, url ->
                runOnUiThread {
                    if (view == current()?.web && !editUrl.isFocused) {
                        editUrl.setText(if (url.contains("jcb.local")) "" else url)
                        updateNavButtons()
                    }
                    // 이 탭의 페이지 이동 → 이 탭의 목록만 리셋
                    VideoStore.clear(view)
                    // 사이트별 JS 차단 갱신 (다음 로드부터 적용, 전역 목록 + 사이트 설정의 독립 목록)
                    runCatching {
                        val h = Uri.parse(url).host ?: ""
                        val want = h.isEmpty() || (!WebCleaner.isJsBlockedFor(h) && !WebCleaner.isInSet("js_deny_hosts", h))
                        if (view.settings.javaScriptEnabled != want) view.settings.javaScriptEnabled = want
                    }
                }
            },
            onPageFinishedCb = { view, url ->
                runOnUiThread {
                    if (view == current()?.web && !editUrl.isFocused) {
                        editUrl.setText(if (url.contains("jcb.local")) "" else url)
                        updateNavButtons()
                        val tab = tabs.firstOrNull { it.web == view }
                        if (tab?.incognito != true &&
                            runCatching { Uri.parse(url).host }.getOrNull() != "jcb.local"
                        ) {
                            runCatching { HistoryRepo.add(this, view.title ?: "", url) }
                        }
                    }
                    // 보안 인증(Cloudflare 등) 페이지에는 주입 건 넘어감 — 인증 스크립트가 훅을 감지해 체크가 안 나타나는 문제 방지
                    if (com.example.streambrowser.browser.SniffingWebViewClient.isSecurityChallengeUrl(url)) return@runOnUiThread
                    runCatching { view.evaluateJavascript("window.__sbMinImg=${VideoJsBridge.imageMinWidth};" + VideoJsBridge.SCANNER_JS, null) }
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
                    // 오버레이 차단 (화면 가리는 고정 레이어 자동 제거, 허용 목록 사이트 제외)
                    val host = runCatching { Uri.parse(url).host ?: "" }.getOrDefault("")
                    if (WebCleaner.overlayEnabled && host.isNotEmpty() && !WebCleaner.isOverlayAllowed(host)) {
                        runCatching { view.evaluateJavascript(WebCleaner.overlayJs(), null) }
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
                // 팝업 차단: 사용자 제스처 없는 새창 (허용 목록 사이트 제외)
                if (WebCleaner.popupEnabled && !isUserGesture) {
                    val openerHost = runCatching { Uri.parse(view.url ?: "").host ?: "" }.getOrDefault("")
                    if (openerHost.isEmpty() || !WebCleaner.isPopupAllowed(openerHost)) {
                        com.example.streambrowser.util.JcToast.show(this@MainActivity, R.string.popup_blocked)
                        return false
                    }
                }
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
                enterImmersive()
                container.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                // Soul 스타일: 네이티브 전체화면에서 화면 오래 누륵면 톱니 버튼 표시
                view.setOnLongClickListener {
                    onVideoLongPressed()
                    true
                }
            }

            override fun onHideCustomView() {
                fullscreenView?.let { runCatching { container.removeView(it) } }
                fullscreenView = null
                fullscreenCallback?.onCustomViewHidden()
                fullscreenCallback = null
                topBar.visibility = View.VISIBLE
                bottomBar.visibility = View.VISIBLE
                stopAutoRotate()
                if (!jsFsActive) exitImmersive()
            }

            // 사이트 파일 업로드(input type=file) — 파일 선택기 띄우고 결과를 콜백
            override fun onShowFileChooser(
                webView: WebView, filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                return runCatching {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }, REQ_FILE_PICK
                    )
                    true
                }.getOrElse {
                    pendingFileCallback = null
                    false
                }
            }

            // 위치 정보 요청 — 허용/거부 다이얼로그
            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: android.webkit.GeolocationPermissions.Callback
            ) {
                val originText = origin.trim().removePrefix("https://").removePrefix("http://")
                    .trimEnd('/').ifEmpty { origin }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.perm_location_title)
                    .setMessage(getString(R.string.perm_location_msg, originText))
                    .setPositiveButton(R.string.btn_allow) { _, _ -> callback.invoke(origin, true, false) }
                    .setNegativeButton(R.string.btn_deny) { _, _ -> callback.invoke(origin, false, false) }
                    .setOnCancelListener { callback.invoke(origin, false, false) }
                    .show()
            }

            // WebRTC 카메라/마이크 권한 요청 — 허용/거부 다이얼로그
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                val wantsCapture = request.resources.any {
                    it == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                        it == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                if (!wantsCapture) {
                    request.deny()
                    return
                }
                val originText = request.origin?.toString()?.trim()?.removePrefix("https://")
                    ?.removePrefix("http://")?.trimEnd('/')?.ifEmpty { request.origin.toString() } ?: ""
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.perm_media_title)
                        .setMessage(getString(R.string.perm_media_msg, originText))
                        .setPositiveButton(R.string.btn_allow) { _, _ -> runCatching { request.grant(request.resources) } }
                        .setNegativeButton(R.string.btn_deny) { _, _ -> request.deny() }
                        .setOnCancelListener { request.deny() }
                        .show()
                }
            }
        }
        wv.webChromeClient = chromeClient
        wv.setOnLongClickListener { showHitMenu(wv) }
        // WebView가 렌더링 못 하는 파일(일반 다운로드) 감지 — 기존엔 리스너가 없어 클릭핸도 아무 일 없었음
        wv.setDownloadListener { u, _, contentDisposition, mime, _ ->
            when {
                u.startsWith("magnet:") -> handleTorrentLink(u)
                u.substringBefore('#').substringBefore('?').lowercase().endsWith(".torrent") -> handleTorrentLink(u)
                else -> downloadFile(u, contentDisposition, mime)
            }
        }
        val tab = Tab(wv, incognito)
        tabs.add(tab)
        current = tabs.size - 1
        runCatching { wv.addJavascriptInterface(VideoJsBridge(wv), "StreamBrowser") }
        when {
            url == NEW_TAB_URL -> loadSpeedDial(wv)
            url.isNotEmpty() -> wv.loadUrl(url)
        }
        showCurrent()
        return tab
    }

    /* ---------- ① 속도 다이얼 새 탭 페이지 (Chrome/삼성 스타일) ---------- */

    private val NEW_TAB_URL = "jcb://newtab"

    private fun loadSpeedDial(wv: WebView) {
        // 방문 기록에서 자주 간 도메인 상위 8개 (검색 결과/속도 다이얼 자체 제외)
        val seen = LinkedHashMap<String, String>() // 도메인 -> 제목
        for ((title, url, _) in com.example.streambrowser.db.HistoryRepo.all(this)) {
            val host = runCatching { Uri.parse(url).host ?: "" }.getOrDefault("")
            if (host.isEmpty() || host == "jcb.local") continue
            if ((host.endsWith("google.com") || host == "google.com") && url.contains("/search")) continue
            if ((host.endsWith("bing.com") || host == "bing.com") && url.contains("/search")) continue
            val domain = host.removePrefix("www.")
            if (!seen.containsKey(domain)) seen[domain] = title.ifBlank { domain }
            if (seen.size >= 8) break
        }
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) "#202124" else "#FFFFFF"
        val fg = if (dark) "#E8EAED" else "#202124"
        val sub = if (dark) "#9AA0A6" else "#5F6368"
        val colors = listOf("#1A73E8", "#E8710A", "#188038", "#9334E6", "#D93025", "#009688", "#3F51B5", "#795548")
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>")
        sb.append("<style>body{background:$bg;margin:0;font-family:sans-serif;padding:28px 16px}")
        sb.append("h1{color:$fg;font-size:17px;margin:0 0 24px 4px;font-weight:600}")
        sb.append(".grid{display:grid;grid-template-columns:repeat(4,1fr);gap:22px 10px;max-width:560px;margin:0 auto}")
        sb.append(".tile{text-decoration:none;text-align:center;-webkit-tap-highlight-color:transparent}")
        sb.append(".circ{width:52px;height:52px;border-radius:50%;margin:0 auto;color:#fff;font-size:22px;font-weight:bold;display:flex;align-items:center;justify-content:center}")
        sb.append(".lbl{color:$sub;font-size:11px;margin:7px auto 0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:84px}")
        sb.append(".empty{color:$sub;font-size:13px;text-align:center;margin-top:40px;line-height:1.6}</style></head><body>")
        sb.append("<h1>").append(org.json.JSONObject.quote(getString(R.string.newtab_title)).removeSurrounding("\"")).append("</h1>")
        if (seen.isEmpty()) {
            sb.append("<div class='empty'>").append(getString(R.string.newtab_empty)).append("</div>")
        } else {
            sb.append("<div class='grid'>")
            seen.entries.forEachIndexed { i, e ->
                val c = colors[i % colors.size]
                sb.append("<a class='tile' href='https://").append(e.key).append("'>")
                sb.append("<div class='circ' style='background:").append(c).append("'>")
                sb.append(e.value.trimStart().first().uppercaseChar())
                sb.append("</div><div class='lbl'>").append(e.key).append("</div></a>")
            }
            sb.append("</div>")
        }
        sb.append("</body></html>")
        wv.loadDataWithBaseURL("https://jcb.local/newtab", sb.toString(), "text/html", "utf-8", null)
    }

    /* ---------- ② 화면 가장자리 스와이프 = 뒤로/앞으로 (Chrome/엣지/삼성 스타일) ---------- */

    private fun attachEdgeSwipe(target: View) {
        val dm = resources.displayMetrics
        val edgeZone = (26 * dm.density)
        val threshold = (56 * dm.density)
        var startX = -1f
        var startY = -1f
        var isLeftEdge = false
        var fired = false
        target.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fired = false
                    isLeftEdge = ev.x < edgeZone
                    if (isLeftEdge || target.width - ev.x < edgeZone) {
                        startX = ev.x; startY = ev.y
                    } else startX = -1f
                }
                MotionEvent.ACTION_MOVE -> {
                    if (startX >= 0f && !fired) {
                        val dx = ev.x - startX
                        val dy = ev.y - startY
                        if (kotlin.math.abs(dx) > threshold && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f) {
                            fired = true
                            val web = current()?.web
                            if (isLeftEdge && dx > 0) {
                                if (web?.canGoBack() == true) web.goBack()
                            } else if (!isLeftEdge && dx < 0) {
                                if (web?.canGoForward() == true) web.goForward()
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> startX = -1f
            }
            false
        }
    }

    /* ---------- ⑥ 사이트별 설정 (Chrome 자물쇠 아이콘 스타일) ---------- */

    private fun setHostIn(key: String, host: String, member: Boolean) {
        val cur = WebCleaner.hostsOf(key).toMutableSet()
        if (member) cur.add(host.lowercase()) else cur.remove(host.lowercase())
        WebCleaner.setHosts(key, cur)
    }

    private fun showSiteSettingsDialog() {
        val web = current()?.web ?: return
        val url = web.url ?: return
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return
        val items = arrayOf(
            getString(R.string.site_js),
            getString(R.string.site_popup),
            getString(R.string.site_overlay),
            getString(R.string.site_ads)
        )
        val checked = booleanArrayOf(
            runCatching { web.settings.javaScriptEnabled }.getOrDefault(true),
            WebCleaner.isPopupAllowed(host),
            WebCleaner.isOverlayAllowed(host),
            AdBlocker.isHostAllowed(host)
        )
        AlertDialog.Builder(this)
            .setTitle(host)
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                when (which) {
                    // 자바스크립트: 사이트 설정 전용 독립 목록(js_deny_hosts) — 전역 js_block과 무관
                    0 -> {
                        setHostIn("js_deny_hosts", host, !isChecked)
                        runCatching { web.settings.javaScriptEnabled = isChecked }
                        if (!isChecked) com.example.streambrowser.util.JcToast.show(this, getString(R.string.js_denied_site))
                    }
                    1 -> setHostIn("popup_allow_hosts", host, isChecked)
                    2 -> setHostIn("overlay_allow_hosts", host, isChecked)
                    3 -> {
                        val cur = AdBlocker.adAllowHosts().toMutableSet()
                        if (isChecked) cur.add(host.lowercase()) else cur.remove(host.lowercase())
                        AdBlocker.setAllowHosts(cur)
                    }
                }
            }
            .setPositiveButton(getString(R.string.btn_ok)) { _, _ ->
                web.reload()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun closeTab(index: Int) {
        if (tabs.size <= 1) {
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.last_tab))
            return
        }
        val t = tabs[index]
        t.web.url?.let { if (it.isNotEmpty()) closedTabs.addLast(it to t.incognito) }
        while (closedTabs.size > 10) closedTabs.removeFirst()
        t.web.destroy()
        tabs.removeAt(index)
        if (current >= tabs.size) current = tabs.size - 1
        showCurrent()
    }

    private fun showCurrent() {
        container.removeAllViews()
        current()?.let { container.addView(it.web) }
        // removeAllViews가 플로팅 버튼도 제거하므로 다시 올리고, 현재 탭 JS 보고 전까지 숨김
        videoMenuBtn?.let {
            container.addView(it)
            it.visibility = View.GONE
        }
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
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.render_recovered))
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
        dlg.findViewById<Button>(R.id.btnCloseAll).setOnClickListener {
            // 모든 탭 닫고 홈 탭 하나만 새로 열기 (실수 방지 확인)
            AlertDialog.Builder(this)
                .setTitle(R.string.dlg_tabs_close_all)
                .setMessage(R.string.close_all_tabs_confirm)
                .setPositiveButton(R.string.btn_ok) { _, _ ->
                    tabs.forEach { runCatching { it.web.destroy() } }
                    tabs.clear()
                    current = 0
                    createTab(prefs.getString("home_url", HOME) ?: HOME)
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
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
        val prefKey: String?,     // pref-based check state
        val state: (() -> Boolean)? = null,  // custom check state (e.g. per-site allow)
        val action: () -> Unit
    )
    private class MenuGroup(
        val groupRes: Int,
        val iconRes: Int,
        val items: List<MenuEntry>,
        /** 설정되면 아코디언 대신 헤더 탭으로 바로 실행 (하위 항목 없음) */
        val direct: (() -> Unit)? = null,
        /** 동적 라벨 (null 이면 groupRes 문자열) */
        val label: (() -> String)? = null
    )

    private sealed class MenuRow {
        class Shortcut : MenuRow()
        class Quick(val entry: MenuEntry) : MenuRow()
        class Header(val groupRes: Int) : MenuRow()
        class Child(val entry: MenuEntry) : MenuRow()
    }

    private var menuDialog: Dialog? = null
    private var panelDragStartY = -1f
    private var expandedGroup: Int? = null

    /** 상단 단축키 그리드 항목 (아이콘, 라벨, 동작) */
    private class ShortcutSpec(
        val iconRes: Int,
        val labelRes: Int,
        val state: (() -> Boolean)? = null,
        val action: () -> Unit
    )

    /** Top shortcut grid: actions + ON/OFF toggles with live state tint */
    private fun shortcutItems(): List<ShortcutSpec> {
        val s = fun(res: Int) = getString(res)
        return listOf(
            ShortcutSpec(R.drawable.ic_tabs, R.string.menu_new_tab) {
                createTab(NEW_TAB_URL)
                menuDialog?.dismiss()
            },
            ShortcutSpec(R.drawable.ic_incognito, R.string.menu_new_incognito) {
                createTab(NEW_TAB_URL, incognito = true)
                com.example.streambrowser.util.JcToast.show(this, s(R.string.incognito_on))
                menuDialog?.dismiss()
            },
            ShortcutSpec(R.drawable.ic_bookmark_add, R.string.menu_add_bookmark) {
                showAddBookmarkDialog()
                menuDialog?.dismiss()
            },
            ShortcutSpec(R.drawable.ic_bookmark, R.string.menu_bookmarks) {
                startActivityForResult(Intent(this, com.example.streambrowser.ui.BookmarksActivity::class.java), 1)
                menuDialog?.dismiss()
            },
            ShortcutSpec(R.drawable.ic_history, R.string.menu_history) {
                startActivityForResult(Intent(this, com.example.streambrowser.ui.HistoryActivity::class.java), 2)
                menuDialog?.dismiss()
            },
            ShortcutSpec(R.drawable.ic_download, R.string.menu_downloads) {
                startActivity(Intent(this, com.example.streambrowser.ui.DownloadsActivity::class.java))
                menuDialog?.dismiss()
            },
            ShortcutSpec(R.drawable.ic_search, R.string.menu_find) {
                findBar.visibility = View.VISIBLE
                findInput.requestFocus()
                menuDialog?.dismiss()
            },
            ShortcutSpec(R.drawable.ic_image, R.string.menu_capture) {
                captureCurrentPage()
                menuDialog?.dismiss()
            },
            // ON/OFF toggles (state shown as blue tint, no toasts)
            ShortcutSpec(R.drawable.ic_desktop, R.string.menu_desktop, { prefs.getBoolean("desktop", false) }) {
                val on = !prefs.getBoolean("desktop", false)
                prefs.edit().putBoolean("desktop", on).apply()
                tabs.forEach {
                    it.web.settings.userAgentString = if (on) UA_DESKTOP else UA_MOBILE
                    it.web.reload()
                }
                rebuildMenu()
            },
            ShortcutSpec(R.drawable.ic_adblock, R.string.menu_adblock, { AdBlocker.enabled }) {
                AdBlocker.enabled = !AdBlocker.enabled
                rebuildMenu()
            },
            ShortcutSpec(R.drawable.ic_open_in_new, R.string.menu_popup_block, { WebCleaner.popupEnabled }) {
                WebCleaner.popupEnabled = !WebCleaner.popupEnabled
                rebuildMenu()
            },
            ShortcutSpec(R.drawable.ic_pip, R.string.menu_auto_pip, { prefs.getBoolean("auto_pip", false) }) {
                val on = !prefs.getBoolean("auto_pip", false)
                prefs.edit().putBoolean("auto_pip", on).apply()
                updatePipParams()
                rebuildMenu()
            }
        )
    }
    /** 설정 그룹 (아코디언): 용도별 그룹으로 정리 */
    /** Settings groups (accordion), grouped by purpose. Toggles live in the shortcut grid. */
    private fun menuGroups(): List<MenuGroup> {
        val s = fun(res: Int) = getString(res)
        val currentHost = runCatching { Uri.parse(current()?.web?.url ?: "").host ?: "" }.getOrDefault("")
        return listOf(
            MenuGroup(R.string.group_page, R.drawable.ic_share, listOf(
                MenuEntry(s(R.string.menu_share), R.drawable.ic_share, null) { sharePage() },
                MenuEntry(s(R.string.menu_copy_url), R.drawable.ic_copy, null) { copyCurrentUrl() },
                MenuEntry(s(R.string.menu_open_external), R.drawable.ic_open_in_new, null) { openInExternalApp() },
                MenuEntry(s(R.string.menu_system_downloads), R.drawable.ic_download, null) { openSystemDownloads() },
                MenuEntry(s(R.string.menu_print), R.drawable.ic_list, null) { printPage() }
            )),
            MenuGroup(R.string.group_cleaner, R.drawable.ic_shield, listOf(
                MenuEntry(s(R.string.menu_adblock), R.drawable.ic_adblock, "adblock") {
                    val on = !prefs.getBoolean("adblock", true)
                    prefs.edit().putBoolean("adblock", on).apply()
                    AdBlocker.enabled = on
                },
                MenuEntry(s(R.string.menu_ad_filters), R.drawable.ic_filter_list, null) {
                    startActivity(Intent(this, com.example.streambrowser.ui.AdFiltersActivity::class.java))
                },
                MenuEntry(s(R.string.menu_ad_whitelist), R.drawable.ic_check_circle, null) {
                    startActivity(Intent(this, com.example.streambrowser.ui.HostListActivity::class.java)
                        .putExtra(com.example.streambrowser.ui.HostListActivity.EXTRA_TITLE, s(R.string.menu_ad_whitelist))
                        .putExtra(com.example.streambrowser.ui.HostListActivity.EXTRA_PREF, "ad_allow_hosts")
                        .putExtra(com.example.streambrowser.ui.HostListActivity.EXTRA_CURRENT, currentHost))
                },
                MenuEntry(s(R.string.menu_overlay_block), R.drawable.ic_layers, "overlay_block") {
                    val on = !prefs.getBoolean("overlay_block", true)
                    prefs.edit().putBoolean("overlay_block", on).apply()
                    WebCleaner.overlayEnabled = on
                },
                MenuEntry(s(R.string.menu_overlay_whitelist), R.drawable.ic_check_circle, null) {
                    startActivity(Intent(this, com.example.streambrowser.ui.HostListActivity::class.java)
                        .putExtra(com.example.streambrowser.ui.HostListActivity.EXTRA_TITLE, s(R.string.menu_overlay_whitelist))
                        .putExtra(com.example.streambrowser.ui.HostListActivity.EXTRA_PREF, "overlay_allow_hosts")
                        .putExtra(com.example.streambrowser.ui.HostListActivity.EXTRA_CURRENT, currentHost))
                },
                MenuEntry(s(R.string.menu_popup_block), R.drawable.ic_open_in_new, "popup_block") {
                    val on = !prefs.getBoolean("popup_block", true)
                    prefs.edit().putBoolean("popup_block", on).apply()
                    WebCleaner.popupEnabled = on
                },
                MenuEntry(getString(R.string.menu_popup_mode) + ": " + s(if (WebCleaner.popupBlockAll) R.string.popup_mode_all else R.string.popup_mode_ad), R.drawable.ic_tune, null) {
                    showPopupModeDialog()
                },
                MenuEntry(s(R.string.menu_popup_whitelist), R.drawable.ic_check_circle, null) {
                    startActivity(Intent(this, com.example.streambrowser.ui.HostListActivity::class.java)
                        .putExtra(com.example.streambrowser.ui.HostListActivity.EXTRA_TITLE, s(R.string.menu_popup_whitelist))
                        .putExtra(com.example.streambrowser.ui.HostListActivity.EXTRA_PREF, "popup_allow_hosts")
                        .putExtra(com.example.streambrowser.ui.HostListActivity.EXTRA_CURRENT, currentHost))
                },
                MenuEntry(s(R.string.menu_js_block), R.drawable.ic_code, "js_block") {
                    val on = !prefs.getBoolean("js_block", false)
                    prefs.edit().putBoolean("js_block", on).apply()
                    WebCleaner.jsBlockEnabled = on
                    current()?.web?.reload()
                },
                MenuEntry(s(R.string.menu_js_block_sites), R.drawable.ic_check_circle, null) {
                    startActivity(Intent(this, com.example.streambrowser.ui.HostListActivity::class.java)
                        .putExtra(com.example.streambrowser.ui.HostListActivity.EXTRA_TITLE, s(R.string.menu_js_block_sites))
                        .putExtra(com.example.streambrowser.ui.HostListActivity.EXTRA_PREF, "js_block_hosts")
                        .putExtra(com.example.streambrowser.ui.HostListActivity.EXTRA_CURRENT, currentHost))
                },
                MenuEntry(s(R.string.menu_app_block), R.drawable.ic_block, "app_block") {
                    val on = !prefs.getBoolean("app_block", true)
                    prefs.edit().putBoolean("app_block", on).apply()
                    WebCleaner.appBlockEnabled = on
                }
            )),
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
                },
                MenuEntry(getString(R.string.menu_img_min) + ": " + prefs.getInt("img_min_px", 300) + "px", R.drawable.ic_image, null) {
                    showImageMinDialog()
                }
            )),
            MenuGroup(R.string.vpn_title, R.drawable.ic_lock, emptyList(),
                direct = {
                    runCatching { startActivity(Intent(this, com.example.streambrowser.ui.VpnActivity::class.java)) }
                },
                label = { vpnMenuLabel() }
            ),
            MenuGroup(R.string.group_torrent, R.drawable.ic_download, listOf(
                // 토렌트 지원: ON이면 .torrent/magnet을 토렌트로 받고(공유 파일 자동 다운로드), OFF면 .torrent만 일반 파일로
                MenuEntry(s(R.string.menu_torrent_play), R.drawable.ic_play, "torrent_play") {
                    val on = !prefs.getBoolean("torrent_play", false)
                    prefs.edit().putBoolean("torrent_play", on).apply()
                },
                MenuEntry(s(R.string.menu_torrent_downloads), R.drawable.ic_download, null) {
                    startActivity(Intent(this, com.example.streambrowser.ui.TorrentDownloadsActivity::class.java))
                },
                MenuEntry(getString(R.string.menu_torrent_max, prefs.getInt("torrent_max", 2)), R.drawable.ic_tune, null) {
                    showIntPickerDialog(getString(R.string.menu_torrent_max, prefs.getInt("torrent_max", 2)), 1, 5, prefs.getInt("torrent_max", 2)) { v ->
                        prefs.edit().putInt("torrent_max", v).apply()
                        rebuildMenu()
                    }
                },
                MenuEntry(torrentRateLabel(), R.drawable.ic_tune, null) {
                    showTorrentRateDialog()
                },
                MenuEntry(s(R.string.menu_torrent_open), R.drawable.ic_open_in_new, null) {
                    showTorrentOpenDialog()
                },
                // 다운 완료 후 시딩 중지 — 완료되는 순간 일시 정지해 업로드가 아예 안 생기게 함
                MenuEntry(s(R.string.menu_torrent_no_seed), R.drawable.ic_block, "torrent_no_seed") {
                    val on = !prefs.getBoolean("torrent_no_seed", false)
                    prefs.edit().putBoolean("torrent_no_seed", on).apply()
                }
            )),
            MenuGroup(R.string.group_privacy, R.drawable.ic_incognito, listOf(
                MenuEntry(s(R.string.menu_block_images), R.drawable.ic_image, "block_images") {
                    val on = !com.example.streambrowser.browser.WebCleaner.blockImages
                    com.example.streambrowser.browser.WebCleaner.blockImages = on
                    tabs.forEach { runCatching { it.web.settings.blockNetworkImage = on } }
                },
                MenuEntry(s(R.string.menu_clear_data), R.drawable.ic_close, null) {
                    confirmClearData()
                }
            )),
            MenuGroup(R.string.group_general, R.drawable.ic_settings, listOf(
                MenuEntry(getString(R.string.menu_search_engine) + ": " + searchEngineLabel(), R.drawable.ic_search, null) {
                    showSearchEngineDialog()
                },
                MenuEntry(s(R.string.menu_suggest), R.drawable.ic_search, "suggest") {
                    val on = !prefs.getBoolean("suggest", true)
                    prefs.edit().putBoolean("suggest", on).apply()
                    if (!on) suggestPopup?.dismiss()
                },
                MenuEntry(getString(R.string.menu_theme) + ": " + themeModeLabel(), R.drawable.ic_dark, null) {
                    showThemeDialog()
                },
                MenuEntry(s(R.string.menu_text_size), R.drawable.ic_expand_more, null) {
                    showTextSizeDialog()
                },
                MenuEntry(s(R.string.menu_home_setting), R.drawable.ic_home, null) {
                    showHomeDialog()
                },
                MenuEntry(s(R.string.menu_reopen_tab), R.drawable.ic_history, null) {
                    reopenClosedTab()
                },
                MenuEntry(s(R.string.menu_close_all_tabs), R.drawable.ic_close, null) {
                    confirmCloseAllTabs()
                },
                MenuEntry(s(R.string.menu_restore_tabs), R.drawable.ic_tabs, "restore_tabs") {
                    val on = !prefs.getBoolean("restore_tabs", true)
                    prefs.edit().putBoolean("restore_tabs", on).apply()
                },
                MenuEntry(s(R.string.menu_language), R.drawable.ic_menu_vert, null) {
                    showLanguageDialog()
                },
                MenuEntry(s(R.string.menu_backup), R.drawable.ic_download, null) {
                    showBackupDialog()
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
        for (g in menuGroups()) {
            rows += MenuRow.Header(g.groupRes)
            // direct 실행 그룹(VPN)은 하위 항목 없음 — 헤더 탭으로 바로 실행
            if (g.direct == null && expandedGroup == g.groupRes) {
                g.items.forEach { rows += MenuRow.Child(it) }
            }
        }
        return rows
    }

    /** 메뉴 VPN 항목 라벨 — 현재 연결 상태를 함께 표시 */
    private fun vpnMenuLabel(): String {
        val base = getString(R.string.vpn_title)
        return when (JcVpnService.state) {
            "CONNECTED" -> "$base — ${JcVpnService.stateProfile}"
            "CONNECTING", "AUTH", "ASSIGN_IP", "WAIT" -> "$base — ${getString(R.string.vpn_state_connecting_short)}"
            "ERROR" -> "$base — ${getString(R.string.vpn_failed)}"
            else -> base
        }
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

    /** 각 설정의 실제 동작 기본값 (메뉴 ON 표시와 일치시키기 위함) */
    private fun prefDefault(key: String): Boolean = when (key) {
        "desktop", "auto_pip", "js_block", "torrent_play", "block_images", "torrent_no_seed" -> false
        "adblock" -> AdBlocker.enabled
        else -> true // restore_tabs, fast_dl, dl_notify, overlay_block, popup_block, app_block, suggest
    }

    /** 팝업 차단 방식: 모든 팝업 / 광고 의심만 (Soul 스타일) */
    private fun showPopupModeDialog() {
        val modes = arrayOf(getString(R.string.popup_mode_all), getString(R.string.popup_mode_ad))
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_popup_mode)
            .setSingleChoiceItems(modes, if (WebCleaner.popupBlockAll) 0 else 1) { dlg, which ->
                WebCleaner.setPopupMode(which == 0)
                dlg.dismiss()
                rebuildMenu()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** SeekBar + number dialog (min..max inclusive) */
    private fun showIntPickerDialog(title: String, min: Int, max: Int, current: Int, onPick: (Int) -> Unit) {
        val label = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER }
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = (current - min).coerceIn(0, max - min)
        }
        label.text = (min + seek.progress).toString()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                label.text = (min + p).toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(label)
            addView(seek)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(getString(R.string.btn_ok)) { _, _ -> onPick(min + seek.progress) }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showSplitDialog() {
        showIntPickerDialog(getString(R.string.dlg_split_title), 1, 10, prefs.getInt("dl_split", 8)) { v ->
            prefs.edit().putInt("dl_split", v).apply()
            rebuildMenu()
        }
    }

    private fun showConnDialog() {
        showIntPickerDialog(getString(R.string.dlg_conn_title), 1, 10, prefs.getInt("dl_conn", 4)) { v ->
            prefs.edit().putInt("dl_conn", v).apply()
            rebuildMenu()
        }
    }

    /** 즐겨찾기 추가: 이름 수정 + 저장 퐔더 선택 가능 */
    private fun showAddBookmarkDialog() {
        val web = current()?.web ?: return
        val url = web.url
        if (url.isNullOrEmpty()) return
        val pageTitle = web.title ?: url

        class Opt(val label: String, val folderId: Long)
        val opts = mutableListOf<Opt>()
        opts += Opt(getString(R.string.dlg_bookmark_root), 0L)
        BookmarkRepo.list(this, 0).filter { it.isFolder }.forEach { opts += Opt(it.title, it.id) }
        opts += Opt(getString(R.string.dlg_bookmark_new_folder), -1L)

        val density = resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val nameInput = EditText(this).apply {
            hint = getString(R.string.dlg_bookmark_name)
            setText(pageTitle)
            setSelection(pageTitle.length)
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, opts.map { it.label })
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, (12 * density).toInt(), padH, 0)
            addView(nameInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(spinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * density).toInt()
            })
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_add_bookmark)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val custom = nameInput.text.toString().trim()
                val finalName = if (custom.isEmpty()) pageTitle else custom
                var parentId = opts[spinner.selectedItemPosition].folderId
                if (parentId == -1L) {
                    val folderInput = EditText(this).apply { hint = getString(R.string.dlg_folder_hint) }
                    AlertDialog.Builder(this)
                        .setTitle(R.string.dlg_new_folder)
                        .setView(folderInput)
                        .setPositiveButton(R.string.action_create) { _, _ ->
                            val fname = folderInput.text.toString().trim()
                            if (fname.isNotEmpty()) {
                                parentId = BookmarkRepo.addFolder(this, fname, 0)
                                BookmarkRepo.add(this, finalName, url, parentId)
                            }
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                } else {
                    BookmarkRepo.add(this, finalName, url, parentId)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
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
                    com.example.streambrowser.util.JcToast.show(this, getString(R.string.menu_home_saved))
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
                    shortcutItems().forEach { spec ->
                        val cell = inflater.inflate(R.layout.item_shortcut_cell, grid, false)
                        val iconV = cell.findViewById<ImageView>(R.id.scIcon)
                        val labelV = cell.findViewById<TextView>(R.id.scLabel)
                        iconV.setImageResource(spec.iconRes)
                        labelV.text = getString(spec.labelRes)
                        if (spec.state?.invoke() == true) {
                            val accent = resources.getColor(R.color.primary, theme)
                            iconV.setColorFilter(accent)
                            labelV.setTextColor(accent)
                        }
                        cell.setOnClickListener { spec.action() }
                        grid.addView(cell)
                    }
                }
                is MenuRow.Header -> {
                    h.group?.visibility = View.GONE
                    val grp = menuGroups().firstOrNull { it.groupRes == r.groupRes }
                    h.icon?.setImageResource(grp?.iconRes ?: R.drawable.ic_menu_vert)
                    h.title?.text = grp?.label?.invoke() ?: getString(r.groupRes)
                    h.title?.setTypeface(h.title?.typeface, android.graphics.Typeface.BOLD)
                    if (grp?.direct != null) {
                        // 직접 실행 그룹: 화살표 없이 탭하면 바로 실행
                        h.state?.visibility = View.GONE
                        h.itemView.setOnClickListener {
                            menuDialog?.dismiss()
                            grp.direct.invoke()
                        }
                    } else {
                        h.state?.visibility = View.VISIBLE
                        h.state?.text = if (expandedGroup == r.groupRes) "▾" else "▸"
                        h.state?.setTextColor(Color.parseColor("#9AA0A6"))
                        h.itemView.setOnClickListener {
                            expandedGroup = if (expandedGroup == r.groupRes) null else r.groupRes
                            rebuildMenu()
                        }
                    }
                }
                is MenuRow.Quick, is MenuRow.Child -> {
                    h.group?.visibility = View.GONE
                    // 하위 메뉴(Child)는 상위 그룹보다 들여쓰기 — 단계 구분이 한눈에 보이도록
                    val indentDp = if (r is MenuRow.Child) 26 else 0
                    h.itemView.setPaddingRelative(
                        (indentDp * resources.displayMetrics.density).toInt(), 0, 0, 0
                    )
                    val e = (r as? MenuRow.Quick)?.entry ?: (r as MenuRow.Child).entry
                    h.icon?.setImageResource(e.iconRes)
                    h.title?.text = e.title
                    h.title?.setTypeface(h.title?.typeface, android.graphics.Typeface.NORMAL)
                    val checked = when {
                        e.state != null -> e.state.invoke()
                        e.prefKey == "adblock" -> AdBlocker.enabled
                        e.prefKey != null -> prefs.getBoolean(e.prefKey, prefDefault(e.prefKey))
                        else -> false
                    }
                    h.state?.visibility =
                        if ((e.prefKey != null || e.state != null) && checked) View.VISIBLE else View.GONE
                    h.state?.text = getString(R.string.on_state)
                    h.state?.setTextColor(resources.getColor(R.color.primary, theme))
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

    // ---------------- 백업 / 복원 ----------------

    private val bk = com.example.streambrowser.util.BackupManager

    /** 범위 선택(설정/즐겨찾기/VPN) 공용 다이얼로그 */
    private fun showScopeDialog(
        titleRes: Int, available: Set<String>, checkedDefault: Boolean,
        onOk: (scopes: Set<String>) -> Unit
    ) {
        val keys = listOf(bk.SCOPE_SETTINGS, bk.SCOPE_BOOKMARKS, bk.SCOPE_VPN)
            .filter { it in available }
        val labels = keys.map {
            when (it) {
                bk.SCOPE_SETTINGS -> getString(R.string.backup_scope_settings)
                bk.SCOPE_BOOKMARKS -> getString(R.string.backup_scope_bookmarks)
                else -> getString(R.string.backup_scope_vpn)
            }
        }.toTypedArray()
        val checked = BooleanArray(keys.size) { checkedDefault }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.btn_ok) { d, _ ->
                val scopes = keys.filterIndexed { i, _ -> checked[i] }.toSet()
                if (scopes.isEmpty()) {
                    d.dismiss()
                    return@setPositiveButton
                }
                onOk(scopes)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** 비밀번호 입력 공용 다이얼로그 */
    private fun showPasswordDialog(titleRes: Int, msgRes: Int, onOk: (String) -> Unit) {
        val ed = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.backup_password_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(msgRes)
            .setView(ed)
            .setPositiveButton(R.string.btn_ok) { d, _ ->
                val pw = ed.text.toString()
                if (pw.length < 4) {
                    com.example.streambrowser.util.JcToast.show(this, getString(R.string.backup_password_short))
                    d.dismiss()
                    return@setPositiveButton
                }
                onOk(pw)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showBackupDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_dlg_title)
            .setItems(arrayOf(getString(R.string.backup_action_backup), getString(R.string.backup_action_restore))) { d, which ->
                d.dismiss()
                if (which == 0) startBackupFlow() else startBackupRestore()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun startBackupFlow() {
        val all = setOf(bk.SCOPE_SETTINGS, bk.SCOPE_BOOKMARKS, bk.SCOPE_VPN)
        showScopeDialog(R.string.backup_dlg_title, all, true) { scopes ->
            showPasswordDialog(R.string.backup_password_title, R.string.backup_password_msg) { pw ->
                val plain = runCatching { bk.buildBackup(this, scopes) }.getOrNull()
                if (plain == null) {
                    com.example.streambrowser.util.JcToast.show(this, getString(R.string.backup_failed))
                    return@showPasswordDialog
                }
                val encrypted = runCatching { bk.encrypt(plain, pw) }.getOrNull()
                if (encrypted == null) {
                    com.example.streambrowser.util.JcToast.show(this, getString(R.string.backup_failed))
                    return@showPasswordDialog
                }
                pendingBackupBytes = encrypted
                AlertDialog.Builder(this)
                    .setTitle(R.string.backup_dest_title)
                    .setItems(
                        arrayOf(
                            getString(R.string.backup_to_cloud),
                            getString(R.string.backup_to_local)
                        )
                    ) { d, which ->
                        d.dismiss()
                        if (which == 0) {
                            val name = "JCBrowser-backup-" +
                                java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                                    .format(java.util.Date()) + ".jcbak"
                            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "application/octet-stream"
                                putExtra(Intent.EXTRA_TITLE, name)
                            }
                            runCatching { startActivityForResult(i, REQ_BACKUP_SAVE) }
                                .onFailure { pendingBackupBytes = null }
                        } else {
                            saveBackupLocal(encrypted)
                        }
                    }
                    .show()
            }
        }
    }

    /** 로컬 백업 — 공용 Download/JC Browser 폴더에 MediaStore 로 저장 */
    private fun saveBackupLocal(bytes: ByteArray) {
        val name = "JCBrowser-backup-" +
            java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                .format(java.util.Date()) + ".jcbak"
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val ok = runCatching {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/JC Browser")
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw java.io.IOException("insert failed")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw java.io.IOException("open failed")
            }.isSuccess
            com.example.streambrowser.util.JcToast.show(
                this,
                getString(if (ok) R.string.backup_done else R.string.backup_failed)
            )
        } else {
            // API 28 이하: 외부 저장소 직접 쓰기 (WRITE_EXTERNAL_STORAGE 필요)
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 91)
                pendingBackupBytes = bytes
                return
            }
            writeBackupLocalLegacy(bytes, name)
        }
    }

    private fun writeBackupLocalLegacy(bytes: ByteArray, name: String) {
        val ok = runCatching {
            val dir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ), "JC Browser"
            )
            dir.mkdirs()
            java.io.File(dir, name).writeBytes(bytes)
        }.isSuccess
        com.example.streambrowser.util.JcToast.show(
            this,
            getString(if (ok) R.string.backup_done else R.string.backup_failed)
        )
    }

    /** 복원 — 파일 선택 → 비밀번호 → 범위 확인 → 적용 */
    private fun startBackupRestore() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(i, REQ_BACKUP_OPEN) }
    }

    private fun handleBackupPicked(data: Intent?) {
        val uri = data?.data ?: return
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.backup_bad_file))
            return
        }
        showPasswordDialog(R.string.backup_restore_password_title, R.string.backup_restore_password_msg) { pw ->
            val plain = try {
                bk.decrypt(bytes, pw)
            } catch (e: com.example.streambrowser.util.BackupManager.BackupException) {
                com.example.streambrowser.util.JcToast.show(
                    this,
                    getString(
                        if (e.message == "not a JC Browser backup file") R.string.backup_bad_file
                        else R.string.backup_wrong_password
                    )
                )
                return@showPasswordDialog
            }
            val available = runCatching { bk.scopesIn(plain) }.getOrDefault(emptySet())
            if (available.isEmpty()) {
                com.example.streambrowser.util.JcToast.show(this, getString(R.string.backup_bad_file))
                return@showPasswordDialog
            }
            showScopeDialog(R.string.backup_restore_scopes, available, true) { scopes ->
                val replaceBm = bk.SCOPE_BOOKMARKS in scopes
                val ok = runCatching {
                    bk.applyRestore(this, plain, scopes, replaceBm)
                }.isSuccess
                com.example.streambrowser.util.JcToast.show(
                    this,
                    getString(if (ok) R.string.backup_restore_done else R.string.backup_restore_failed)
                )
                if (ok) recreate()
            }
        }
    }

    private fun showDownloadFolderDialog() {
        val values = arrayOf("public", "custom", "app")
        val labels = arrayOf(
            getString(R.string.folder_public),
            getString(R.string.folder_custom),
            getString(R.string.folder_app)
        )
        val cur = values.indexOf(prefs.getString("dl_folder", "public")).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_dl_folder))
            .setSingleChoiceItems(labels, cur) { d, which ->
                if (values[which] == "custom") {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    runCatching { startActivityForResult(i, 5) }
                        .onFailure {
                            com.example.streambrowser.util.JcToast.show(this, getString(R.string.folder_pick_failed))
                        }
                    d.dismiss()
                } else {
                    prefs.edit().putString("dl_folder", values[which]).apply()
                    d.dismiss()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private var videoMenuPopup: PopupWindow? = null
    private var videoHideRunnable: Runnable? = null

    private fun runVideoJs(js: String) {
        runCatching { current()?.web?.evaluateJavascript(js, null) }
    }

    private var orientListener: OrientationEventListener? = null

    /** Rotate only the fullscreen view: 0=portrait, 90=landscape */
    private fun applyFullscreenRotation(v: View, deg: Float) {
        val dm = resources.displayMetrics
        val lp = v.layoutParams as FrameLayout.LayoutParams
        if (deg == 0f || deg == 180f) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            lp.width = dm.heightPixels
            lp.height = dm.widthPixels
        }
        lp.gravity = Gravity.CENTER
        v.layoutParams = lp
        v.rotation = deg
    }

    private fun rotateFullscreen(deg: Float) {
        val v = rotationTarget() ?: return
        stopAutoRotate()
        applyFullscreenRotation(v, deg)
        rotatedWeb = if (v is WebView) v else null
    }

    private fun startAutoRotate() {
        stopAutoRotate()
        orientListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val v = rotationTarget() ?: run { stopAutoRotate(); return }
                // 기기 회전 방향에 맞춰 영상 회전 (landscape에서 뒤집히지 않게)
                val target = when {
                    degrees >= 45 && degrees < 135 -> 270f
                    degrees >= 135 && degrees < 225 -> 180f
                    degrees >= 225 && degrees < 315 -> 90f
                    else -> 0f
                }
                if (v.rotation != target) applyFullscreenRotation(v, target)
                rotatedWeb = if (v is WebView) v else null
            }
        }
        if (orientListener?.canDetectOrientation() == true) orientListener?.enable()
    }

    private fun stopAutoRotate() {
        orientListener?.disable()
        orientListener = null
    }

    /* ---------- Soul 스타일: 동영상 위 플로팅 메뉴 버튼 ---------- */

    private var videoMenuBtn: TextView? = null

    private fun ensureVideoMenuButton(): TextView {
        videoMenuBtn?.let { return it }
        val dm = resources.displayMetrics
        val size = (38 * dm.density).toInt()
        val b = TextView(this).apply {
            text = "⚙"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0x73000000.toInt())
                cornerRadius = 999f
            }
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = (12 * dm.density).toInt()
                bottomMargin = (112 * dm.density).toInt()
            }
            setOnClickListener { showVideoMenu() }
        }
        container.addView(b)
        videoMenuBtn = b
        return b
    }

    private val hideGearRunnable = Runnable { videoMenuBtn?.visibility = View.GONE }

    /** Soul 스타일: 전체화면에서 동영상을 길게 누륾면 톱니 버튼이 2초간만 표시 (설정 안 하면 자동 숨김) */
    private fun onVideoLongPressed() {
        if (jsFsActive || fullscreenView != null) {
            val b = ensureVideoMenuButton()
            b.visibility = View.VISIBLE
            container.bringChildToFront(b)
            mainHandler.removeCallbacks(hideGearRunnable)
            mainHandler.postDelayed(hideGearRunnable, 2000)
        } else {
            showVideoMenu()
        }
    }

    /** 회전 대상: HTML5 fullscreen 커스텀 뷰가 아니면 JS 풀스크린 모드의 WebView를 회전 */
    private var rotatedWeb: WebView? = null

    private fun rotationTarget(): View? = fullscreenView ?: (if (jsFsActive) current()?.web else null)

    private fun restoreRotatedWeb() {
        rotatedWeb?.let { w ->
            w.rotation = 0f
            w.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rotatedWeb = null
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
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        GradientDrawable().apply {
                            setColor(Color.parseColor("#E8F0FE"))
                            cornerRadius = 10f
                        }
                    )
                    addState(
                        intArrayOf(),
                        GradientDrawable().apply { setColor(Color.WHITE) }
                    )
                }
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
            addView(row(getString(if (jsFsActive || fullscreenView != null) R.string.vm_fs_off else R.string.vm_fs_on)) {
                when {
                    // 네이티브 HTML5 전체화면: 커스텀 뷰 종료
                    fullscreenView != null -> chromeClient.onHideCustomView()
                    jsFsActive -> {
                        runVideoJs("window.__sbFsOff();")
                        jsFsActive = false
                        restoreRotatedWeb()
                        topBar.visibility = View.VISIBLE
                        bottomBar.visibility = View.VISIBLE
                        exitImmersive()
                    }
                    else -> {
                        runVideoJs("window.__sbFsOn();")
                        jsFsActive = true
                        topBar.visibility = View.GONE
                        bottomBar.visibility = View.GONE
                        enterImmersive()
                    }
                }
            })
            // 회전 메뉴: HTML5 fullscreen 커스텀 뷰 + JS 풀스크린 모드 둘 다 지원
            if (fullscreenView != null || jsFsActive) {
                addView(row(getString(R.string.vm_landscape)) {
                    rotateFullscreen(90f)
                })
                addView(row(getString(R.string.vm_portrait)) {
                    rotateFullscreen(0f)
                })
                addView(row(getString(R.string.vm_auto_rotate)) {
                    startAutoRotate()
                })
            }
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
        // 테마 설정(다크/라이트/시스템) 기준으로 WebView 다크닝 적용.
        // API 33+: forceDark는 무효이고 algorithmic darkening은 '앱 테마가 다크'일 때만 동작하므로
        // 테마 변경 시 액티비티 recreate로 테마까지 함께 바뀐 뒤 여기서 허용 여부만 제어한다.
        val dark = com.example.streambrowser.util.ThemeHelper.isDark(this)
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { settings.setAlgorithmicDarkeningAllowed(dark) }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                settings.forceDark = if (dark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
            }
        }
    }

    // ------------------------------------------------------- 테마 (다크/라이트/시스템)

    /** 일반 파일 다운로드: 시스템 다운로드 매니저로 Downloads 폴더에 저장 (표준 브라우저 동작) */
    private fun downloadFile(u: String, contentDisposition: String?, mime: String?) {
        runCatching {
            val name = android.webkit.URLUtil.guessFileName(u, contentDisposition, mime)
            val req = android.app.DownloadManager.Request(Uri.parse(u)).apply {
                setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                current()?.web?.settings?.userAgentString?.let { addRequestHeader("User-Agent", it) }
                runCatching { CookieManager.getInstance().getCookie(u) }.getOrNull()?.let { c ->
                    if (c.isNotEmpty()) addRequestHeader("Cookie", c)
                }
                if (!mime.isNullOrEmpty()) setMimeType(mime)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(req)
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.torrent_file_downloading))
        }.onFailure {
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.notif_failed))
        }
    }

    /** magnet/.torrent 링크 진입점 — 토렌트 지원 ON일 때만 토렌트로, OFF면 .torrent만 일반 다운로드 */
    private fun handleTorrentLink(url: String) {
        if (prefs.getBoolean("torrent_play", false)) {
            runCatching {
                startActivity(Intent(this, com.example.streambrowser.ui.TorrentActivity::class.java)
                    .putExtra(com.example.streambrowser.ui.TorrentActivity.EXTRA_URL, url))
            }.onFailure {
                com.example.streambrowser.util.JcToast.show(this, getString(R.string.torrent_unavailable))
            }
        } else {
            if (url.startsWith("magnet:")) {
                // 마그넷은 일반 다운로드 불가 — 토렌트 지원 켜기 안내
                com.example.streambrowser.util.JcToast.show(this, getString(R.string.torrent_need_enable))
                return
            }
            // 토렌트 지원 OFF: 시스템 다운로드 매니저로 .torrent 파일만 받기
            runCatching {
                val name = android.webkit.URLUtil.guessFileName(url, null, "application/x-bittorrent")
                val req = android.app.DownloadManager.Request(Uri.parse(url)).apply {
                    setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(req)
                com.example.streambrowser.util.JcToast.show(this, getString(R.string.torrent_file_downloading))
            }.onFailure {
                com.example.streambrowser.util.JcToast.show(this, getString(R.string.notif_failed))
            }
        }
    }

    /** 토렌트 완료 파일들을 설정된 다운로드 위치(dl_folder)로 납품 */
    private fun exportTorrentJob(job: com.example.streambrowser.torrent.TorrentManager.Job) {
        if (job.exported) return
        job.exported = true
        kotlin.concurrent.thread {
            job.files().forEach { f ->
                com.example.streambrowser.download.DownloadFolder.export(this, f, mimeOfTorrent(f.name))
            }
        }
    }

    private fun mimeOfTorrent(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"; "mov" -> "video/quicktime"; "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"; "ogg" -> "audio/ogg"; "wav" -> "audio/wav"
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"
        "pdf" -> "application/pdf"; "zip" -> "application/zip"
        "txt" -> "text/plain"; "srt" -> "application/x-subrip"
        else -> "application/octet-stream"
    }

    /** 토렌트 속도 제한 메뉴 라벨 — 단위 포함, 0이면 무제한으로 표시 */
    private fun torrentRateLabel(): String {
        fun fmt(v: Int) = if (v <= 0) getString(R.string.rate_unlimited) else "$v KB/s"
        return getString(
            R.string.menu_torrent_rate_fmt,
            fmt(prefs.getInt("torrent_rate_dl", 0)),
            fmt(prefs.getInt("torrent_rate_ul", 0))
        )
    }

    /** 토렌트 다운/업로드 속도 제한 설정 (KB/s, 0=무제한) */
    private fun showTorrentRateDialog() {
        val density = resources.displayMetrics.density
        fun dp(x: Int) = (x * density).toInt()
        fun label(txt: String) = TextView(this).apply {
            text = txt
            textSize = 13f
            setTextColor(resources.getColor(R.color.icon_tint, theme))
        }
        fun edit(current: Int) = EditText(this).apply {
            setText(current.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(resources.getColor(R.color.text_primary, theme))
            setHintTextColor(resources.getColor(R.color.icon_tint, theme))
        }
        val edDl = edit(prefs.getInt("torrent_rate_dl", 0))
        val edUl = edit(prefs.getInt("torrent_rate_ul", 0))
        val note = TextView(this).apply {
            text = getString(R.string.dlg_rate_note)
            textSize = 12f
            setTextColor(resources.getColor(R.color.icon_tint, theme))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(24)
            setPadding(pad, dp(6), pad, dp(8))
            addView(label(getString(R.string.dlg_rate_dl_hint)))
            addView(edDl)
            addView(label(getString(R.string.dlg_rate_ul_hint)))
            addView(edUl)
            addView(note)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_torrent_rate))
            .setView(box)
            .setPositiveButton(getString(R.string.btn_ok)) { _, _ ->
                val dl = edDl.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                val ul = edUl.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                prefs.edit().putInt("torrent_rate_dl", dl).putInt("torrent_rate_ul", ul).apply()
                com.example.streambrowser.torrent.TorrentManager.applyRateLimits(dl, ul)
                rebuildMenu()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /** 메뉴에서 토렌트/magnet 직접 열기 */
    private fun showTorrentOpenDialog() {
        val edit = android.widget.EditText(this).apply {
            hint = getString(R.string.open_torrent_hint)
            setTextColor(resources.getColor(R.color.text_primary, theme))
            setHintTextColor(resources.getColor(R.color.icon_tint, theme))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.menu_torrent_open)
            .setView(edit)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                val u = edit.text.toString().trim()
                if (u.isNotEmpty()) handleTorrentLink(u)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .setNeutralButton(getString(R.string.torrent_pick_file)) { _, _ -> openTorrentFilePicker() }
            .show()
    }

    /** 파일 관리자에서 .torrent 파일 선택 */
    private fun openTorrentFilePicker() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        runCatching { startActivityForResult(i, 6) }
            .onFailure {
                com.example.streambrowser.util.JcToast.show(this, getString(R.string.torrent_unavailable))
            }
    }

    /** 선택한 .torrent 파일 바로 다운로드 시작 (IO 스레드) */
    private fun importTorrentFile(uri: Uri) {
        kotlin.concurrent.thread {
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            val ti = bytes?.let { runCatching { org.libtorrent4j.TorrentInfo(it) }.getOrNull() }
            if (ti == null) {
                runOnUiThread {
                    com.example.streambrowser.util.JcToast.show(this, getString(R.string.torrent_load_failed))
                }
                return@thread
            }
            val max = prefs.getInt("torrent_max", 2)
            val job = runCatching {
                com.example.streambrowser.torrent.TorrentManager.add(ti, java.io.File(filesDir, "torrent/downloads"), max)
            }.getOrElse { e ->
                runOnUiThread {
                    com.example.streambrowser.util.JcToast.show(
                        this,
                        if (e.message == "max_active") getString(R.string.torrent_max_reached, max)
                        else getString(R.string.torrent_start_failed)
                    )
                }
                return@thread
            }
            runOnUiThread {
                com.example.streambrowser.util.JcToast.show(this, getString(R.string.torrent_started, job.name))
                runCatching {
                    startActivity(Intent(this, com.example.streambrowser.ui.TorrentDownloadsActivity::class.java))
                }
            }
        }
    }

    private fun themeModeLabel(): String = when (com.example.streambrowser.util.ThemeHelper.mode(this)) {
        com.example.streambrowser.util.ThemeHelper.MODE_DARK -> getString(R.string.theme_dark)
        com.example.streambrowser.util.ThemeHelper.MODE_LIGHT -> getString(R.string.theme_light)
        else -> getString(R.string.theme_system)
    }

    /** 테마 선택: 시스템 / 어두움 / 밝음 — 적용을 위해 액티비티를 다시 생성한다 */
    private fun showThemeDialog() {
        val modes = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_dark),
            getString(R.string.theme_light)
        )
        val values = arrayOf(
            com.example.streambrowser.util.ThemeHelper.MODE_SYSTEM,
            com.example.streambrowser.util.ThemeHelper.MODE_DARK,
            com.example.streambrowser.util.ThemeHelper.MODE_LIGHT
        )
        val current = values.indexOf(com.example.streambrowser.util.ThemeHelper.mode(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_theme)
            .setSingleChoiceItems(modes, current) { dlg, which ->
                dlg.dismiss()
                menuDialog?.dismiss()
                val m = values[which]
                if (m != com.example.streambrowser.util.ThemeHelper.mode(this)) {
                    com.example.streambrowser.util.ThemeHelper.setMode(this, m)
                    recreate()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showTextSizeDialog() {
        showIntPickerDialog(getString(R.string.dlg_text_size), 50, 150, prefs.getInt("text_zoom", 100)) { v ->
            val zoom = (v / 5) * 5
            prefs.edit().putInt("text_zoom", zoom).apply()
            tabs.forEach { it.web.settings.textZoom = zoom }
            rebuildMenu()
        }
    }

    /** 이미지 다운로드 목록에 올릴 최소 가로 픽셀 (100~2000, 50 단위) */
    private fun showImageMinDialog() {
        showIntPickerDialog(getString(R.string.menu_img_min), 100, 2000, prefs.getInt("img_min_px", 300)) { v ->
            val px = ((v + 25) / 50) * 50
            prefs.edit().putInt("img_min_px", px).apply()
            VideoJsBridge.imageMinWidth = px
            rebuildMenu()
        }
    }

    private fun captureCurrentPage() {
        val wv = current()?.web ?: return
        runCatching {
            val bmp = Bitmap.createBitmap(wv.width, wv.contentHeight.coerceAtLeast(wv.height), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val wasHw = wv.layerType == View.LAYER_TYPE_HARDWARE
            if (wasHw) wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            wv.draw(canvas)
            if (wasHw) wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "JC Browser").apply { mkdirs() }
            val f = File(dir, "capture_${System.currentTimeMillis()}.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.capture_saved, f.absolutePath), long = true)
        }.onFailure {
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.capture_failed))
        }
    }

    private fun currentHost(): String? =
        runCatching { Uri.parse(current()?.web?.url ?: "").host }.getOrNull()

    private fun isCurrentHostAllowed(): Boolean =
        currentHost()?.let { AdBlocker.isHostAllowed(it) } == true

    /** 이 사이트 광고 허용 토글 (전역 차단은 유지, 예외 사이트만 허용) */
    private fun toggleAllowAds() {
        val host = currentHost()
        if (host.isNullOrEmpty()) {
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.invalid_page))
            return
        }
        AdBlocker.toggleAllowHost(this, host)
        current()?.web?.reload()
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
                com.example.streambrowser.util.JcToast.show(this, getString(R.string.clear_done))
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showAbout() {
        val ver = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: ""
        AlertDialog.Builder(this)
            .setIcon(R.mipmap.ic_launcher)
            .setTitle(getString(R.string.app_name))
            .setMessage("Version $ver\n\nMade by Junyoung Choi")
            .setPositiveButton(getString(R.string.btn_ok), null)
            .show()
    }

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
                        com.example.streambrowser.util.JcToast.show(this, getString(R.string.image_dl_started))
                    }
                    2 -> copyText(imgUrl, getString(R.string.image_copied))
                }
            }
            .show()
    }

    private fun copyText(text: String, msg: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        com.example.streambrowser.util.JcToast.show(this, msg)
    }

    private fun showBackHistory(): Boolean {
        val wv = current()?.web ?: return false
        val stack = runCatching { wv.copyBackForwardList() }.getOrNull() ?: return false
        if (stack.size == 0) {
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.history_empty))
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
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.pip_unsupported))
            return
        }
        // 동영상 재생 중일 때만 PIP 진입. 재생 상태를 직접 확인 (JS 브리지 상태가 오래됐을 수 있음)
        val wv = current()?.web
        if (wv == null) {
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.no_video_playing))
            return
        }
        wv.evaluateJavascript(
            "(function(){var v=document.querySelector('video');return !!(v && !v.paused && !v.ended);})()"
        ) { res ->
            runOnUiThread {
                val playing = res?.trim() == "true"
                if (!playing) {
                    com.example.streambrowser.util.JcToast.show(this, getString(R.string.no_video_playing))
                    return@runOnUiThread
                }
                runCatching {
                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                    enterPictureInPictureMode(params)
                }.onFailure {
                    com.example.streambrowser.util.JcToast.show(this, getString(R.string.pip_failed))
                }
            }
        }
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setAutoEnterEnabled(prefs.getBoolean("auto_pip", false) && videoPlaying)
                    .build()
                setPictureInPictureParams(params)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 자동 PIP도 동영상 재생 중일 때만
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.R &&
            prefs.getBoolean("auto_pip", false) && videoPlaying
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
            u = if (u.contains(".") && !u.contains(" ")) "https://$u" else searchUrl(u)
        }
        suggestPopup?.dismiss()
        current()?.web?.loadUrl(u)
        // 엔터 입력 후 키보드 내리고 포커스 해제
        editUrl.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(editUrl.windowToken, 0)
    }

    /** 설정된 검색엔진의 검색 URL 생성 */
    private fun searchUrl(query: String): String {
        val enc = java.net.URLEncoder.encode(query, "UTF-8")
        return when (prefs.getString("search_engine", "google")) {
            "bing" -> "https://www.bing.com/search?q=$enc"
            "duckduckgo" -> "https://duckduckgo.com/?q=$enc"
            "naver" -> "https://search.naver.com/search.naver?query=$enc"
            "daum" -> "https://search.daum.net/search?q=$enc"
            else -> "https://www.google.com/search?q=$enc"
        }
    }

    private fun searchEngineLabel(): String = when (prefs.getString("search_engine", "google")) {
        "bing" -> "Bing"
        "duckduckgo" -> "DuckDuckGo"
        "naver" -> "Naver"
        "daum" -> "Daum"
        else -> "Google"
    }

    private fun showSearchEngineDialog() {
        val values = arrayOf("google", "bing", "duckduckgo", "naver", "daum")
        val labels = arrayOf("Google", "Bing", "DuckDuckGo", "Naver", "Daum")
        val cur = values.indexOf(prefs.getString("search_engine", "google")).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_search_engine))
            .setSingleChoiceItems(labels, cur) { d, which ->
                prefs.edit().putString("search_engine", values[which]).apply()
                d.dismiss()
                rebuildMenu()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /* ---------- 페이지 공통 기능 (Chrome/Edge/Firefox 공통) ---------- */

    private fun sharePage() {
        val url = current()?.web?.url ?: return
        val title = current()?.web?.title ?: ""
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        runCatching { startActivity(Intent.createChooser(i, getString(R.string.menu_share))) }
    }

    private fun copyCurrentUrl() {
        val url = current()?.web?.url ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
        com.example.streambrowser.util.JcToast.show(this, getString(R.string.url_copied))
    }

    private fun openInExternalApp() {
        val url = current()?.web?.url ?: return
        runCatching { Uri.parse(url) }.getOrNull()?.let { uri ->
            val i = Intent(Intent.ACTION_VIEW, uri)
            runCatching { startActivity(Intent.createChooser(i, getString(R.string.menu_open_external))) }
                .onFailure {
                    com.example.streambrowser.util.JcToast.show(this, getString(R.string.torrent_unavailable))
                }
        }
    }

    /** 시스템 다운로드 앱 열기 (DownloadListener로 받은 zip/pdf 등은 여기서 관리) */
    private fun openSystemDownloads() {
        runCatching {
            startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
        }
    }

    private fun printPage() {
        val web = current()?.web ?: run {
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.print_failed))
            return
        }
        // 래핑된 base 대신 원본 base context 로 PrintManager 획득 (mContext 가 Activity 여야 함)
        val pm = (baseBeforeWrap ?: this).getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager
        if (pm == null) {
            com.example.streambrowser.util.JcToast.show(this, getString(R.string.print_failed))
            return
        }
        val jobName = web.title?.takeIf { it.isNotBlank() } ?: "JC Browser"
        val attrs = android.print.PrintAttributes.Builder().build()
        // 표준 어댑터(API 21+, 인자 없음) 먼저, 실패 시 구형 인자 버전으로 폴back.
        // 예외 메시지를 토스트에 포함 — 기기별 인쇄 서비스 문제 원확인용
        var err: String? = null
        var ok = runCatching {
            pm.print(jobName, web.createPrintDocumentAdapter(), attrs)
            true
        }.getOrElse { err = it.message ?: it.javaClass.simpleName; false }
        if (!ok) {
            ok = runCatching {
                @Suppress("DEPRECATION")
                pm.print(jobName, web.createPrintDocumentAdapter(jobName), attrs)
                true
            }.getOrElse { err = it.message ?: it.javaClass.simpleName; false }
        }
        if (!ok) {
            com.example.streambrowser.util.JcToast.show(this,
                getString(R.string.print_failed) + (err?.let { " — $it" } ?: ""), long = true)
        }
    }

    /* ---------- 닫은 탭 복구 (Chrome/Firefox 공통) ---------- */

    private val closedTabs = ArrayDeque<Pair<String, Boolean>>() // (url, incognito), 최근 닫은 순

    private fun reopenClosedTab() {
        while (closedTabs.isNotEmpty()) {
            val (url, incognito) = closedTabs.removeLast()
            if (url.isNotEmpty() && url != "about:blank") {
                createTab(url, incognito)
                return
            }
        }
        com.example.streambrowser.util.JcToast.show(this, getString(R.string.none_closed))
    }

    private fun confirmCloseAllTabs() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_close_all_tabs)
            .setMessage(R.string.close_all_tabs_confirm)
            .setPositiveButton(R.string.btn_ok) { _, _ -> closeAllTabs() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun closeAllTabs() {
        val keep = current()
        tabs.filter { it != keep }.forEach { it.web.destroy() }
        tabs.clear()
        tabs.add(keep!!)
        current = 0
        showCurrent()
    }

    /* ---------- 검색어 자동완성 제안 (Chrome/엣지 스타일) ---------- */

    private var suggestPopup: PopupWindow? = null
    private var suggestQuery = ""
    private val suggestRunnable = Runnable { fetchSuggestions(suggestQuery) }

    private fun fetchSuggestions(q: String) {
        Thread {
            val list = runCatching {
                val enc = java.net.URLEncoder.encode(q, "UTF-8")
                val conn = java.net.URL("https://duckduckgo.com/ac/?q=$enc&type=list")
                    .openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                val arr = org.json.JSONArray(body)
                val out = mutableListOf<String>()
                if (arr.length() > 1) {
                    val items = arr.optJSONArray(1) ?: org.json.JSONArray()
                    for (i in 0 until minOf(items.length(), 6)) {
                        when (val it = items.opt(i)) {
                            is String -> out += it
                            is org.json.JSONObject -> it.optString("phrase").takeIf { p -> p.isNotBlank() }?.let(out::add)
                        }
                    }
                }
                out
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (editUrl.text.toString().trim() == q) showSuggestions(list)
            }
        }.start()
    }

    private fun showSuggestions(items: List<String>) {
        if (items.isEmpty() || !editUrl.isFocused) return
        val listView = android.widget.ListView(this)
        listView.adapter = ArrayAdapter(this, R.layout.item_suggest, items)
        // focusable=false — 팝업이 포커스를 빼앗지 않아 입력 중 키보드가 날아가지 않음
        val pw = PopupWindow(
            listView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        )
        pw.setBackgroundDrawable(ColorDrawable(resources.getColor(R.color.sheet_bg, theme)))
        pw.elevation = 16f
        pw.isOutsideTouchable = true
        listView.setOnItemClickListener { _, _, pos, _ ->
            pw.dismiss()
            suggestPopup = null
            editUrl.setText(items[pos])
            loadUrl()
        }
        pw.showAsDropDown(topBar, 0, 0)
        suggestPopup = pw
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 사이트 파일 업로드(input type=file) 결과
        if (requestCode == REQ_FILE_PICK) {
            val cb = pendingFileCallback
            pendingFileCallback = null
            if (resultCode == RESULT_OK) {
                val uri = data?.data
                cb?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
            } else {
                cb?.onReceiveValue(null)
            }
        }
        // 백업 저장 (클라우드/SAF)
        if (requestCode == REQ_BACKUP_SAVE) {
            val bytes = pendingBackupBytes
            pendingBackupBytes = null
            if (resultCode == RESULT_OK && bytes != null) {
                val ok = runCatching {
                    data?.data?.let { uri ->
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    }
                }.isSuccess
                com.example.streambrowser.util.JcToast.show(
                    this,
                    getString(if (ok) R.string.backup_done else R.string.backup_failed)
                )
            }
        }
        // 백업 파일 선택 → 복원
        if (requestCode == REQ_BACKUP_OPEN && resultCode == RESULT_OK) {
            handleBackupPicked(data)
        }
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
                        com.example.streambrowser.util.JcToast.show(this, getString(R.string.bookmark_imported, n))
                    }
                }
            }.onFailure {
                com.example.streambrowser.util.JcToast.show(this, getString(R.string.bookmark_import_failed))
            }
        }
        // 즐겨찾기 HTML 내보내기
        if (requestCode == 4 && resultCode == RESULT_OK) {
            runCatching {
                data?.data?.let { uri ->
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(BookmarkRepo.exportHtml(this).toByteArray(Charsets.UTF_8))
                    }
                    com.example.streambrowser.util.JcToast.show(this, getString(R.string.bookmark_exported))
                }
            }.onFailure {
                com.example.streambrowser.util.JcToast.show(this, getString(R.string.bookmark_export_failed))
            }
        }
        // 사용자 지정 다운로드 폴더 (SAF 트리)
        if (requestCode == 5 && resultCode == RESULT_OK) {
            runCatching {
                data?.data?.let { uri ->
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    prefs.edit().putString("dl_folder", "custom").putString("dl_folder_tree", uri.toString()).apply()
                    com.example.streambrowser.util.JcToast.show(this, getString(R.string.dl_folder_saved))
                }
            }.onFailure {
                com.example.streambrowser.util.JcToast.show(this, getString(R.string.folder_pick_failed))
            }
        }
        // 파일 관리자에서 선택한 .torrent
        if (requestCode == 6 && resultCode == RESULT_OK) {
            data?.data?.let { importTorrentFile(it) }
        }
    }

    /** API 28 이하 로컬 백업용 WRITE_EXTERNAL_STORAGE 결과 */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 91) {
            val bytes = pendingBackupBytes
            pendingBackupBytes = null
            val name = "JCBrowser-backup-" +
                java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                    .format(java.util.Date()) + ".jcbak"
            if (bytes != null && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                writeBackupLocalLegacy(bytes, name)
            } else {
                com.example.streambrowser.util.JcToast.show(this, getString(R.string.backup_failed))
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
            restoreRotatedWeb()
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
        mainHandler.removeCallbacks(hideGearRunnable)
        tabs.forEach { it.web.destroy() }
        tabs.clear()
        super.onDestroy()
    }
}
