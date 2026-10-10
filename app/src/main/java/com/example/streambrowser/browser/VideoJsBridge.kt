package com.example.streambrowser.browser

import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * 페이지에 주입하는 JS가 감지한 미디어 소스를 안드로이드로 전달하는 다리.
 * - video/audio 태그의 src 수집 (blob:/MSE 포함)
 * - XHR/fetch 응답 본문에서 .m3u8/.mpd/.mp4 등 URL 추출 (FirePlayer 계열 대응)
 * - owner: 이 다리가 붙은 WebView (탭별 미디어 목록 구분용)
 */
class VideoJsBridge(private val owner: WebView? = null) {

    @JavascriptInterface
    fun addVideo(url: String, tag: String, page: String, force: String?) {
        if (url.isBlank()) return
        val kind = when {
            force == "HLS" || force == "DASH" -> force
            url.startsWith("blob:") -> "BLOB"
            ".m3u8" in url -> "HLS"
            ".mpd" in url -> "DASH"
            ".mp4" in url || ".m4v" in url || ".mov" in url -> "MP4"
            ".webm" in url -> "WEBM"
            ".flv" in url -> "FLV"
            else -> "MEDIA"
        }
        VideoStore.add(owner, DetectedVideo(url = url, page = page, kind = kind))
    }

    @JavascriptInterface
    fun addImage(url: String, page: String) {
        if (url.isBlank()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        VideoStore.add(owner, DetectedVideo(url = url, page = page, kind = "IMG"))
    }

    @JavascriptInterface
    fun addPoster(videoUrl: String, poster: String) {
        VideoStore.addPoster(owner, videoUrl, poster)
    }

    /** Soul 브라우저 스타일: 동영상 길게 누르기 → 네이티브 메뉴 호출 */
    @JavascriptInterface
    fun videoLongPress() {
        onVideoLongPress?.invoke()
    }

    /** 페이지에 재생 가능한 video 태그가 있는지 주기적 보고 (플로팅 메뉴 버튼 표시용) */
    @JavascriptInterface
    fun videoPresent(found: Boolean) {
        onVideoPresence?.invoke(owner, found)
    }

    /** SPA 등에서 주소만 바뀌는 페이지 전환 감지 → 이 탭의 목록만 리셋 */
    @JavascriptInterface
    fun pageChanged() {
        VideoStore.clear(owner)
    }

    /** JS -> 네이티브 상태 전달 (전체화면 등) */
    @JavascriptInterface
    fun videoFsChanged(on: Boolean) {
        onVideoFsChange?.invoke(on)
    }

    /** 동영상 재생/일시정지 상태 전달 (PIP 등에서 사용) */
    @JavascriptInterface
    fun videoState(playing: Boolean) {
        onVideoStateChange?.invoke(playing)
    }

    /** 전체화면 제스처: 밝기 조절 (norm: 세로 이동량/화면 높이, 아래로 내리면 -) */
    @JavascriptInterface
    fun gestureBright(norm: Double) {
        onGestureBright?.invoke(norm.toFloat())
    }

    /** 전체화면 제스처: 음량 조절 (norm: 세로 이동량/화면 높이) */
    @JavascriptInterface
    fun gestureVolume(norm: Double) {
        onGestureVolume?.invoke(norm.toFloat())
    }

    companion object {
        @Volatile
        var onVideoLongPress: (() -> Unit)? = null

        @Volatile
        var onVideoPresence: ((WebView?, Boolean) -> Unit)? = null

        @Volatile
        var onVideoFsChange: ((Boolean) -> Unit)? = null

        @Volatile
        var onVideoStateChange: ((Boolean) -> Unit)? = null

        @Volatile
        var onGestureBright: ((Float) -> Unit)? = null

        @Volatile
        var onGestureVolume: ((Float) -> Unit)? = null

        /** 이미지 다운로드 목록에 올릴 최소 가로 픽셀 (설정에서 변경) */
        @Volatile
        var imageMinWidth: Int = 300

        /**
         * 페이지에 주입할 스캐너 스크립트.
         * HTML <head>에 삽입되므로 플레이어 스크립트보다 먼저 실행되어 XHR/fetch를 훅할 수 있다.
         */
        const val SCANNER_JS = """
(function(){
  if (window.__sbScanner) return;
  window.__sbScanner = true;

  var MEDIA_RE = /https?:\/\/[^\s"'<>]+?\.(m3u8|mpd|mp4|m4v|webm|flv)(\?[^\s"'<>]*)?/gi;
  var M3U8_ANY_RE = /https?:\/\/[^\s"'<>]*m3u8[^\s"'<>]*/gi;

  function scanText(t){
    try{
      if(!t || t.length > 300000) return;
      t = t.replace(/\\\//g, '/'); /* JSON 이스케이프 복원 */
      var m;
      while((m = MEDIA_RE.exec(t)) !== null){
        var abs = m[0];
        try{ abs = new URL(abs, location.href).href; }catch(x){}
        window.StreamBrowser.addVideo(abs, 'NET', location.href);
      }
      /* 확장자 없이 토큰만으로 m3u8 을 가리키는 URL도 본문에서 잡아냄 */
      M3U8_ANY_RE.lastIndex = 0;
      while((m = M3U8_ANY_RE.exec(t)) !== null){
        var abs2 = m[0];
        try{ abs2 = new URL(abs2, location.href).href; }catch(x){}
        window.StreamBrowser.addVideo(abs2, 'NET', location.href);
      }
    }catch(e){}
  }

  /* 요청 URL 자체가 미디어 링크면(상대 경로 포함) 바로 보고 — m3u8 토큰은 경로 어디든 허용 */
  function reportUrl(u){
    try{
      if(u && (/m3u8/i.test(u) || /\.(mpd|mp4|m4v|webm|flv)(\?|#|$)/i.test(u))){
        window.StreamBrowser.addVideo(new URL(u, location.href).href, 'NET', location.href);
      }
    }catch(e){}
  }

  /* 1) video/audio 태그 src 수집 */
  function collect(){
    try{
      var els = document.querySelectorAll('video, audio');
      for (var i=0; i<els.length; i++){
        var el = els[i];
        var src = el.currentSrc || el.src;
        if (src) window.StreamBrowser.addVideo(src, el.tagName, location.href);
        var p = el.poster;
        if (src && p) window.StreamBrowser.addPoster(src, p);
        var ch = el.children;
        for (var j=0; j<ch.length; j++){
          if (ch[j].tagName === 'SOURCE'){
            var u = ch[j].src || ch[j].getAttribute('src');
            if (u) window.StreamBrowser.addVideo(u, 'SOURCE', location.href);
          }
        }
      }
    }catch(e){}
  }
  setInterval(collect, 3000);
  collect();

  /* 1-0) 동영상 재생 상태를 네이티브에 전달 (PIP 자동 진입 여부 판단용) */
  try{
    document.addEventListener('play', function(e){
      try{
        if (e.target && e.target.tagName === 'VIDEO'){
          /* 저장된 배속이 있으면 재생 시작 시 자동 적용 (사이트별) */
          if (window.__sbRate && e.target.playbackRate !== window.__sbRate) e.target.playbackRate = window.__sbRate;
          window.StreamBrowser.videoState(true);
        }
      }catch(x){}
    }, true);
    document.addEventListener('pause', function(e){
      try{ if (e.target && e.target.tagName === 'VIDEO') window.StreamBrowser.videoState(false); }catch(x){}
    }, true);
    document.addEventListener('ended', function(e){
      try{ if (e.target && e.target.tagName === 'VIDEO') window.StreamBrowser.videoState(false); }catch(x){}
    }, true);
  }catch(e){}

  /* 1-1) Soul 스타일: 동영상 길게 누르기 감지 + 네이티브 제어용 헬퍼 노출 */
  (function(){
    var lpTimer = null;
    function clearLp(){ if (lpTimer){ clearTimeout(lpTimer); lpTimer = null; } }
    document.addEventListener('touchstart', function(e){
      clearLp();
      var t = e.target;
      var v = (t && t.closest) ? t.closest('video') : null;
      /* 사이트가 div 래퍼를 전체화면으로 쓰는 경우: 풀스크린 요소면 video 대상으로 간주 */
      if (!v) { try{ if (document.fullscreenElement) v = document.fullscreenElement; }catch(x){} }
      if (!v) return;
      lpTimer = setTimeout(function(){
        try{ window.StreamBrowser.videoLongPress(); }catch(x){}
      }, 600);
    }, {passive:true});
    document.addEventListener('touchend', clearLp, {passive:true});
    document.addEventListener('touchmove', clearLp, {passive:true});
    document.addEventListener('touchcancel', clearLp, {passive:true});
  })();

  /* 1-2) 전체화면 강제 적용/해제 (일부 사이트의 전체화면 버튼 미동작 대응) */
  window.__sbBigVideo = function(){
    var vs = document.querySelectorAll('video');
    var best = null, bestArea = 0;
    for (var i=0;i<vs.length;i++){
      var r = vs[i].getBoundingClientRect();
      var a = r.width * r.height;
      if (a > bestArea){ bestArea = a; best = vs[i]; }
    }
    return best;
  };
  window.__sbFsOn = function(){
    var v = window.__sbBigVideo();
    if (!v) return false;
    v.__sbfs = v.getAttribute('style') || '';
    v.setAttribute('style','position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important;');
    try{ if (v.paused) v.play(); }catch(e){}
    return true;
  };
  window.__sbFsOff = function(){
    var vs = document.querySelectorAll('video');
    for (var i=0;i<vs.length;i++){
      if (vs[i].__sbfs !== undefined){ vs[i].setAttribute('style', vs[i].__sbfs); delete vs[i].__sbfs; }
    }
  };

  /* 1-2a) video 태그 존재 여부를 네이티브에 보고 (플로팅 메뉴 버튼 표시용) */
  var __sbHadVideo = false;
  function __sbReportVideo(){
    try{
      var has = !!window.__sbBigVideo();
      if (has !== __sbHadVideo){
        __sbHadVideo = has;
        window.StreamBrowser.videoPresent(has);
      }
    }catch(e){}
  }
  setInterval(__sbReportVideo, 1500);
  __sbReportVideo();

  /* 1-2) 큰 이미지 수집 (설정된 최소 폭 미만 아이콘/배너 제외) */
  function collectImages(){
    try{
      var minW = window.__sbMinImg || 300;
      var imgs = document.querySelectorAll('img');
      for (var i=0; i<imgs.length; i++){
        var el = imgs[i];
        var w = el.naturalWidth || el.width || 0;
        if (w < minW) continue;
        var src = el.currentSrc || el.src;
        if (src && /^https?:/.test(src)) window.StreamBrowser.addImage(src, location.href);
      }
    }catch(e){}
  }
  setInterval(collectImages, 5000);
  collectImages();

  /* 1-3) SPA 대응: 주소만 바뀌는 페이지 전환 감지 (최상위 창만) */
  try{
    var __sbLastUrl = location.href;
    setInterval(function(){
      try{
        if (window.top !== window) return;
        if (location.href !== __sbLastUrl){
          __sbLastUrl = location.href;
          window.StreamBrowser.pageChanged();
        }
      }catch(e){}
    }, 1000);
  }catch(e){}

  /* 2) XHR 응답 본문에서 미디어 URL 추출 */
  var XOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url){
    this.__u = url;
    return XOpen.apply(this, arguments);
  };
  var XSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function(){
    try{
      this.addEventListener('load', function(){
        try{ reportUrl(this.__u); }catch(e){}
        try{ scanText(this.__u); }catch(e){}
        /* 확장자 없는 재생목록 URL 대비: 응답 본문이 실제 HLS/DASH 목록이면
           URL 자체를 등록 (hls.js/dash.js가 ?token= 만 붙인 경로로 받는 경우 필수) */
        try{
          var rt = this.responseType;
          if (rt === '' || rt === 'text'){
            var xt = this.responseText;
            if (xt && xt.length > 0 && xt.length <= 500000 && this.__u){
              if (xt.indexOf('#EXTM3U') >= 0)
                window.StreamBrowser.addVideo(new URL(this.__u, location.href).href, 'NET', location.href, 'HLS');
              else if (xt.indexOf('<MPD') >= 0)
                window.StreamBrowser.addVideo(new URL(this.__u, location.href).href, 'NET', location.href, 'DASH');
            }
          }
        }catch(e){}
        try{ if (this.responseType === '' || this.responseType === 'text') scanText(this.responseText); }catch(e){}
      });
    }catch(e){}
    return XSend.apply(this, arguments);
  };

  /* 3) fetch 응답 본문에서 미디어 URL 추출 */
  if (window.fetch){
    var ofetch = window.fetch;
    window.fetch = function(input, init){
      var u = (typeof input === 'string') ? input : ((input && input.url) || '');
      return ofetch.apply(this, arguments).then(function(res){
        try{
          reportUrl(u);
          scanText(u);
          var ct = '';
          try{ ct = (res.headers && res.headers.get) ? (res.headers.get('content-type') || '') : ''; }catch(e){}
          if (ct.indexOf('json') >= 0 || ct.indexOf('text') >= 0 || ct.indexOf('mpegurl') >= 0 || /\.(m3u8|mpd)/i.test(u)){
            res.clone().text().then(function(t){
              try{
                if (t && t.length <= 500000 && u){
                  if (t.indexOf('#EXTM3U') >= 0)
                    window.StreamBrowser.addVideo(new URL(u, location.href).href, 'NET', location.href, 'HLS');
                  else if (t.indexOf('<MPD') >= 0)
                    window.StreamBrowser.addVideo(new URL(u, location.href).href, 'NET', location.href, 'DASH');
                }
              }catch(e){}
              scanText(t);
            }).catch(function(){});
          }
        }catch(e){}
        return res;
      });
    };
  }

  /* 4) 전체화면 제스처 — JS 강제 풀스크린(__sbFsOn) 상태에서만 동작.
     좌측 40% 세로 드래그: 밝기 / 우측 40%: 음량 / 가로 드래그: 시크(전체 폭=90초) /
     양쪽 더블탭: 10초 탐색 / 톱니 메뉴에서 터치 잠금 가능 */
  window.__sbFsActive = function(){
    var vs = document.querySelectorAll('video');
    for (var i=0;i<vs.length;i++){ if (vs[i].__sbfs !== undefined) return true; }
    return false;
  };
  window.__sbGest = {lock:false, sx:0, sy:0, cx:0, cy:0, st:0, seekBase:-1, moved:false, lastTap:0, lastTapX:0, ov:null};
  window.__sbGestEnsure = function(){
    var g = window.__sbGest;
    if (g.ov) return g.ov;
    var d = document.createElement('div');
    d.setAttribute('style','position:fixed;top:0;left:0;width:100vw;height:100vh;z-index:2147483647;display:none;');
    var lockBtn = document.createElement('div');
    lockBtn.textContent = '\u{1F512}';
    lockBtn.setAttribute('style','position:absolute;left:50%;bottom:14%;transform:translateX(-50%);font-size:30px;padding:14px;display:none;color:#fff;text-shadow:0 0 6px #000;');
    lockBtn.addEventListener('touchend', function(e){
      e.stopPropagation();
      g.lock = false; g.lockUi();
    }, {passive:true});
    d.appendChild(lockBtn);
    var seekLbl = document.createElement('div');
    seekLbl.setAttribute('style','position:absolute;top:14%;left:50%;transform:translateX(-50%);color:#fff;background:rgba(0,0,0,.55);font:16px monospace;padding:6px 14px;border-radius:6px;display:none;');
    d.appendChild(seekLbl);
    var seekHide = null;
    g.lockUi = function(){ lockBtn.style.display = g.lock ? 'block' : 'none'; };
    g._seekShow = function(sec){
      if (!(sec >= 0)) return;
      var h = Math.floor(sec/3600), m = Math.floor(sec%3600/60), s = Math.floor(sec%60);
      seekLbl.textContent = (h>0 ? h+':' : '') + (m<10?'0':'') + m + ':' + (s<10?'0':'') + s;
      seekLbl.style.display = 'block';
      if (seekHide) clearTimeout(seekHide);
      seekHide = setTimeout(function(){ seekLbl.style.display='none'; }, 1200);
    };
    d.addEventListener('touchstart', function(e){
      var t = e.changedTouches[0];
      g.sx = t.clientX; g.sy = t.clientY; g.cx = t.clientX; g.cy = t.clientY;
      g.st = Date.now(); g.seekBase = -1; g.moved = false;
      if (g.lock) { try{ e.preventDefault(); }catch(x){} }
    }, {passive:false});
    d.addEventListener('touchmove', function(e){
      if (!window.__sbFsActive()) return;
      if (g.lock) { try{ e.preventDefault(); }catch(x){} return; }
      var t = e.changedTouches[0];
      g.cx = t.clientX; g.cy = t.clientY;
      var dx = g.cx - g.sx, dy = g.cy - g.sy;
      if (Math.abs(dx) > 8 || Math.abs(dy) > 8) g.moved = true;
      var v = window.__sbBigVideo();
      if (!v) return;
      if (g.seekBase >= 0 || Math.abs(dx) > Math.abs(dy) * 1.4){
        if (g.seekBase < 0) g.seekBase = v.currentTime;
        var dur = v.duration || 0;
        var nt = g.seekBase + (dx / window.innerWidth) * 90;
        if (dur > 0) nt = Math.min(Math.max(nt, 0), dur);
        try{ v.currentTime = nt; }catch(x){}
        g._seekShow(nt);
      } else if (Math.abs(dy) > 8){
        if (g.sx < window.innerWidth * 0.4) { try{ window.StreamBrowser.gestureBright(dy / window.innerHeight); }catch(x){} }
        else if (g.sx > window.innerWidth * 0.6) { try{ window.StreamBrowser.gestureVolume(dy / window.innerHeight); }catch(x){} }
        g.sy = g.cy;
      }
    }, {passive:false});
    d.addEventListener('touchend', function(e){
      var dt = Date.now() - g.st;
      if (g.lock) return;
      if (!g.moved && dt < 250 && window.__sbFsActive()){
        var t = e.changedTouches[0];
        var now = Date.now();
        if (now - g.lastTap < 350 && Math.abs(t.clientX - g.lastTapX) < 80){
          g.lastTap = 0;
          var v = window.__sbBigVideo();
          if (v){
            var back = t.clientX < window.innerWidth * 0.4;
            var nv = v.currentTime + (back ? -10 : 10);
            if (v.duration > 0) nv = Math.min(Math.max(nv, 0), v.duration);
            try{ v.currentTime = nv; }catch(x){}
          }
        } else {
          g.lastTap = now; g.lastTapX = t.clientX;
          if (window.__sbTapMenu) { try{ window.StreamBrowser.videoLongPress(); }catch(x){} }
        }
      }
    }, {passive:true});
    (document.body || document.documentElement).appendChild(d);
    g.ov = d;
    return d;
  };
  window.__sbLockToggle = function(){
    var g = window.__sbGest;
    window.__sbGestEnsure();
    g.lock = !g.lock;
    g.lockUi();
  };
  /* 풀스크린 진입/해제에 맞춰 제스처 오버레이 표시 전환 */
  setInterval(function(){
    try{
      var d = window.__sbGestEnsure();
      var want = window.__sbFsActive() ? 'block' : 'none';
      if (d.style.display !== want) d.style.display = want;
    }catch(e){}
  }, 1000);
})();
"""
    }
}
