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

    companion object {
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
