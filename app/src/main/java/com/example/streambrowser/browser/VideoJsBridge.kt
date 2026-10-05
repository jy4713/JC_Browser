package com.example.streambrowser.browser

import android.webkit.JavascriptInterface

/**
 * 페이지에 주입하는 JS가 감지한 미디어 소스를 안드로이드로 전달하는 다리.
 * - video/audio 태그의 src 수집 (blob:/MSE 포함)
 * - XHR/fetch 응답 본문에서 .m3u8/.mpd/.mp4 등 URL 추출 (FirePlayer 계열 대응)
 */
class VideoJsBridge {

    @JavascriptInterface
    fun addVideo(url: String, tag: String, page: String) {
        if (url.isBlank()) return
        val kind = when {
            url.startsWith("blob:") -> "BLOB"
            ".m3u8" in url -> "HLS"
            ".mpd" in url -> "DASH"
            ".mp4" in url || ".m4v" in url || ".mov" in url -> "MP4"
            ".webm" in url -> "WEBM"
            ".flv" in url -> "FLV"
            else -> "미지정"
        }
        VideoStore.add(DetectedVideo(url = url, page = page, kind = kind))
    }

    @JavascriptInterface
    fun addImage(url: String, page: String) {
        if (url.isBlank()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        VideoStore.add(DetectedVideo(url = url, page = page, kind = "IMG"))
    }

    @JavascriptInterface
    fun addPoster(videoUrl: String, poster: String) {
        VideoStore.addPoster(videoUrl, poster)
    }

    /** Soul 브라우저 스타일: 동영상 길게 누르기 → 네이티브 메뉴 호출 */
    @JavascriptInterface
    fun videoLongPress() {
        onVideoLongPress?.invoke()
    }

    /** JS -> 네이티브 상태 전달 (전체화면 등) */
    @JavascriptInterface
    fun videoFsChanged(on: Boolean) {
        onVideoFsChange?.invoke(on)
    }

    companion object {
        @Volatile
        var onVideoLongPress: (() -> Unit)? = null

        @Volatile
        var onVideoFsChange: ((Boolean) -> Unit)? = null

        /**
         * 페이지에 주입할 스캐너 스크립트.
         * HTML <head>에 삽입되므로 플레이어 스크립트보다 먼저 실행되어 XHR/fetch를 훅할 수 있다.
         */
        const val SCANNER_JS = """
(function(){
  if (window.__sbScanner) return;
  window.__sbScanner = true;

  var MEDIA_RE = /https?:\/\/[^\s"'<>]+?\.(m3u8|mpd|mp4|m4v|webm|flv)(\?[^\s"'<>]*)?/gi;

  function scanText(t){
    try{
      if(!t || t.length > 300000) return;
      t = t.replace(/\\\//g, '/'); /* JSON 이스케이프 복원 */
      var m;
      while((m = MEDIA_RE.exec(t)) !== null){
        window.StreamBrowser.addVideo(m[0], 'NET', location.href);
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
  setInterval(collect, 1500);
  collect();

  /* 1-1) Soul 스타일: 동영상 길게 누르기 감지 + 네이티브 제어용 헬퍼 노출 */
  (function(){
    var lpTimer = null;
    function clearLp(){ if (lpTimer){ clearTimeout(lpTimer); lpTimer = null; } }
    document.addEventListener('touchstart', function(e){
      clearLp();
      var t = e.target;
      var v = (t && t.closest) ? t.closest('video') : null;
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

  /* 1-2) 큰 이미지 수집 (300px 미만 아이콘/배너 제외) */
  function collectImages(){
    try{
      var imgs = document.querySelectorAll('img');
      for (var i=0; i<imgs.length; i++){
        var el = imgs[i];
        var w = el.naturalWidth || el.width || 0;
        if (w < 300) continue;
        var src = el.currentSrc || el.src;
        if (src && /^https?:/.test(src)) window.StreamBrowser.addImage(src, location.href);
      }
    }catch(e){}
  }
  setInterval(collectImages, 2500);
  collectImages();

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
          if (u) scanText(u);
          var ct = '';
          try{ ct = (res.headers && res.headers.get) ? (res.headers.get('content-type') || '') : ''; }catch(e){}
          if (ct.indexOf('json') >= 0 || ct.indexOf('text') >= 0 || /\.(m3u8|mpd)/i.test(u)){
            res.clone().text().then(scanText).catch(function(){});
          }
        }catch(e){}
        return res;
      });
    };
  }
})();
"""
    }
}
