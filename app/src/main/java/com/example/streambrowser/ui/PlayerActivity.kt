package com.example.streambrowser.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import com.example.streambrowser.R
import java.io.File

/**
 * 낭부 HTML5 플레이어: 외부 앱 없이 팝업(액티비티)으로 영상 재생.
 * WebView의 video 태그로 재생하므로 HLS(m3u8)도 대부분 기기에서 지원됨.
 */
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_PAGE = "page"
    }

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var fsView: View? = null
    private var fsCallback: WebChromeClient.CustomViewCallback? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.example.streambrowser.util.LocaleHelper.wrap(com.example.streambrowser.util.ThemeHelper.wrap(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.streambrowser.util.ThemeHelper.apply(this)
        setContentView(R.layout.activity_player)
        root = findViewById(R.id.playerRoot)
        web = findViewById(R.id.playerWeb)

        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fsView != null) { callback.onCustomViewHidden(); return }
                fsView = view
                fsCallback = callback
                root.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }

            override fun onHideCustomView() {
                fsView?.let { runCatching { root.removeView(it) } }
                fsView = null
                fsCallback?.onCustomViewHidden()
                fsCallback = null
            }
        }

        val escaped = url.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        val html = """<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}
video{width:100vw;height:100vh;object-fit:contain;background:#000}</style>
</head><body>
<video controls autoplay playsinline webkit-playsinline src="$escaped"></video>
</body></html>"""
        if (url.startsWith("file://")) {
            // 로컬 파일(토렌트 순차 재생 등): 같은 폴터에 플레이어 HTML을 쓰고 file://로 로드
            // (loadDataWithBaseURL은 file 하위 리소스 접근이 막혀 불가)
            runCatching {
                val path = url.removePrefix("file://")
                val dir = File(path).parentFile ?: run { finish(); return }
                dir.mkdirs()
                val htmlFile = File(dir, ".jc_play.html")
                htmlFile.writeText(html.replace("src=\"$escaped\"", "src=\"${File(path).name}\""))
                web.loadUrl("file://${htmlFile.absolutePath}")
            }.onFailure { finish() }
        } else {
            web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fsView != null) {
            (web.webChromeClient as? WebChromeClient)?.onHideCustomView()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        runCatching { web.loadUrl("about:blank"); web.destroy() }
        super.onDestroy()
    }
}
