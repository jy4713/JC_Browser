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
            // http/https가 섞인 스트림(m3u8 세그먼트 등) 재생 허용 — 이 플레이어 전용 설정
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
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
        val isHls = ".m3u8" in url.lowercase()
        // m3u8은 WebView <video> 네이티브 미지원(code=4) → hls.js(MSE) 인라인 주입
        val hlsJs: String = if (isHls) runCatching {
            assets.open("hls.min.js").use { it.readBytes() }.toString(Charsets.UTF_8)
        }.getOrDefault("") else ""
        val urlJs = org.json.JSONObject.quote(url)
        val html = """<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}
video{width:100vw;height:100vh;object-fit:contain;background:#000}
#sbErr{display:none;position:fixed;left:8px;right:8px;bottom:8px;background:rgba(60,0,0,.85);color:#fff;font:12px monospace;padding:10px;border-radius:6px;white-space:pre-wrap;word-break:break-all;z-index:9}
#sbSub{position:fixed;left:5%;right:5%;bottom:9%;text-align:center;color:#fff;font:18px/1.4 sans-serif;text-shadow:0 1px 3px #000;white-space:pre-wrap;pointer-events:none;z-index:5}</style>
</head><body>
<video controls autoplay playsinline webkit-playsinline ${if (isHls) "" else "src=\"$escaped\""}></video>
<div id="sbErr"></div>
<script>
var v=document.querySelector('video'),e=document.getElementById('sbErr');
function show(m){e.style.display='block';e.textContent=m;}
v.addEventListener('error',function(){
  var c=v.error?v.error.code:'?';
  show('VIDEO ERROR code='+c+' network='+v.networkState+'\n'+(v.currentSrc||v.src));
});
</script>
${if (isHls) """<script>$hlsJs</script>
<script>
(function(){
  var SRC=$urlJs;
  var nativeTried=false;
  /* hls.js가 실패하면 네이티브 로딩으로 폴드백 재시도 (일부 기기/서버에서 MSE 경로만 실패하는 경우 대응) */
  function tryNative(){
    if(nativeTried) return;
    nativeTried=true;
    try{
      if(window.h) { try{window.h.destroy();}catch(e){} window.h=null; }
      v.removeAttribute('src');
      v.src=SRC;
      v.load();
      var p=v.play(); if(p&&p.catch) p.catch(function(){});
    }catch(e){ show('native fallback error: '+e.message); }
  }
  try{
    if (window.Hls && Hls.isSupported()) {
      var h=new Hls({enableWorker:false});
      window.h=h;
      h.loadSource(SRC); h.attachMedia(v);
      h.on(Hls.Events.ERROR,function(ev,data){
        if(data && data.fatal){
          show('HLS.JS FATAL: '+data.type+'/'+data.details);
          setTimeout(function(){
            if(v.error||v.networkState===3||v.readyState===0) tryNative();
          },800);
        }
      });
      /* 8초 뒤에도 영상이 안 뜨면 네이티브로 전환 */
      setTimeout(function(){
        if(v.readyState===0 && !nativeTried) tryNative();
      },8000);
    } else if (v.canPlayType('application/vnd.apple.mpegurl')) {
      v.src=SRC;
    } else {
      show('HLS not supported on this device');
    }
  }catch(x){show('HLS init error: '+x.message);}
})();
</script>""" else ""}
</body></html>"""
        if (url.startsWith("file://")) {
            // 로컬 파일(토렌트 순차 재생 등): 같은 폴더에 플레이어 HTML을 쓰고 file://로 로드
            // (loadDataWithBaseURL은 file 하위 리소스 접근이 막혀 불가)
            runCatching {
                val path = url.removePrefix("file://")
                val dir = File(path).parentFile ?: run { finish(); return }
                dir.mkdirs()
                val htmlFile = File(dir, ".jc_play.html")
                // 파일명을 URL 인코딩(공백/한글 등) + HTML 이스케이프
                val encName = android.net.Uri.encode(File(path).name)
                    .replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
                /* 자막: 같은 폴터의 같은 이름 .srt/.vtt (Soul 방식). UTF-8이 아니면 EUC-KR로 재시도 */
                val baseName = File(path).nameWithoutExtension
                val subFile = dir.listFiles()?.firstOrNull { f ->
                    f.isFile && f.nameWithoutExtension == baseName &&
                            f.extension.lowercase() in setOf("srt", "vtt")
                }
                val subJs: String
                if (subFile != null) {
                    val raw = subFile.readBytes()
                    var text = raw.toString(Charsets.UTF_8)
                    if (text.count { it == '�' } > 3) {
                        text = String(raw, java.nio.charset.Charset.forName("EUC-KR"))
                    }
                    // "</" 를 "<\/" 로 — 자막 본문에 </script>가 있어도 HTML을 깨지 않게
                    val subJson = org.json.JSONObject.quote(text).replace("</", "<\\/")
                    subJs = """
<div id="sbSub"></div>
<script>
(function(){
  var SUB_ARR = $subJson;
  function tsec(str){
    var p = String(str).trim().replace(',', '.').split(':');
    var r = 0;
    for (var i = 0; i < p.length; i++) r = r * 60 + (parseFloat(p[i]) || 0);
    return r;
  }
  function parseSubs(t){
    t = t.replace(/\r/g, '').replace(/^﻿/, '');
    if (/^\s*WEBVTT/i.test(t)) t = t.replace(/^WEBVTT[^\n]*(\n|$)/, '');
    var blocks = t.split(/\n\n+/);
    var out = [];
    for (var i = 0; i < blocks.length; i++){
      var lines = blocks[i].split('\n');
      if (lines.length < 2) continue;
      var ti = (/-->/).test(lines[0]) ? 0 : ((/-->/).test(lines[1]) ? 1 : -1);
      if (ti < 0) continue;
      var mm = lines[ti].match(/(.+?)\s*-->\s*(.+)/);
      if (!mm) continue;
      var s = tsec(mm[1].split(/\s/)[0]), e = tsec(mm[2].split(/\s/)[0]);
      if (!(e > s)) continue;
      out.push({s: s, e: e, x: lines.slice(ti + 1).join('\n').replace(/<[^>]+>/g, '')});
    }
    return out;
  }
  var subs = parseSubs(SUB_ARR);
  if (subs.length){
    var box = document.getElementById('sbSub');
    v.addEventListener('timeupdate', function(){
      var t = v.currentTime, cur = '';
      for (var i = 0; i < subs.length; i++){
        if (subs[i].s <= t && t <= subs[i].e){ cur = subs[i].x; break; }
        if (subs[i].s > t) break;
      }
      if (box.textContent !== cur) box.textContent = cur;
    });
  }
})();
</script>"""
                } else subJs = ""
                val pageHtml = html
                    .replace("src=\"$escaped\"", "src=\"$encName\"")
                    .replace("</body>", "$subJs</body>")
                htmlFile.writeText(pageHtml)
                web.loadUrl("file://${htmlFile.absolutePath}")
            }.onFailure { finish() }
        } else {
            // 원본 페이지를 base URL로 지정: 상대 URL 해결 + 스트림 서버가 요구하는 오리진/리퍼러 제공
            // (base 없이 로드하면 CDN이 요청을 거부해 0:00/검은 화면이 되는 사이트가 있음)
            val page = intent.getStringExtra(EXTRA_PAGE)
            val base = if (!page.isNullOrEmpty() && (page.startsWith("http://") || page.startsWith("https://"))) page else null
            web.loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
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
