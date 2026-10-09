package com.example.streambrowser.browser

import android.content.Context
import android.content.SharedPreferences

/**
 * Soul Browser "웹 클리너" 계열 기능 관리
 * - 오버레이 차단 (fixed/sticky + z-index 높은 광고성 레이어 자동 제거) + 사이트별 허용
 * - 팝업 차단 (사용자 제스처 없는 새창/리다이렉트 차단) + 모드(전체/광고의심) + 사이트별 허용
 * - 자바스크립트 차단 (사이트 목록 기반) + 사이트 목록
 * - 앱 실행 차단 (intent://, market:// 등 웹→앱 실행 차단)
 * 광고 차단 자체는 AdBlocker가 담당 (여기서는 호스트 목록 공유 헬퍼만 제공)
 */
object WebCleaner {

    @Volatile var overlayEnabled = true
        set(v) { field = v; prefs?.edit()?.putBoolean("overlay_block", v)?.apply() }

    @Volatile var popupEnabled = true
        set(v) { field = v; prefs?.edit()?.putBoolean("popup_block", v)?.apply() }

    /** true: 제스처 없는 모든 새창 차단 / false: 다른 사이트로 가는 것만(광고 의심) 차단 */
    @Volatile var popupBlockAll = true

    @Volatile var jsBlockEnabled = false
        set(v) { field = v; prefs?.edit()?.putBoolean("js_block", v)?.apply() }

    @Volatile var appBlockEnabled = true
        set(v) { field = v; prefs?.edit()?.putBoolean("app_block", v)?.apply() }

    /** 이미지 차단 (데이터 절약) — shouldInterceptRequest에서 img/css-bg 등 모든 이미지 요청 차단 */
    @Volatile var blockImages = false
        set(v) { field = v; prefs?.edit()?.putBoolean("block_images", v)?.apply() }

    private val overlayAllow = HashSet<String>()
    private val popupAllow = HashSet<String>()
    private val jsBlockHosts = HashSet<String>()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs?.let { sp ->
            overlayEnabled = sp.getBoolean("overlay_block", true)
            popupEnabled = sp.getBoolean("popup_block", true)
            popupBlockAll = sp.getBoolean("popup_mode_all", true)
            jsBlockEnabled = sp.getBoolean("js_block", false)
            appBlockEnabled = sp.getBoolean("app_block", true)
            blockImages = sp.getBoolean("block_images", false)
            overlayAllow.clear(); overlayAllow.addAll(sp.getStringSet("overlay_allow_hosts", emptySet())?.map { it.lowercase() } ?: emptyList())
            popupAllow.clear(); popupAllow.addAll(sp.getStringSet("popup_allow_hosts", emptySet())?.map { it.lowercase() } ?: emptyList())
            jsBlockHosts.clear(); jsBlockHosts.addAll(sp.getStringSet("js_block_hosts", emptySet())?.map { it.lowercase() } ?: emptyList())
        }
    }

    // ---------------- 공용 호스트 목록 헬퍼 ----------------

    private fun loadSet(key: String): MutableSet<String> =
        prefs?.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()

    fun hostsOf(key: String): Set<String> = loadSet(key)

    fun setHosts(key: String, hosts: Set<String>) {
        prefs?.edit()?.putStringSet(key, hosts.map { it.lowercase() }.toSet())?.apply()
        when (key) {
            "overlay_allow_hosts" -> { overlayAllow.clear(); overlayAllow.addAll(hosts.map { it.lowercase() }) }
            "popup_allow_hosts" -> { popupAllow.clear(); popupAllow.addAll(hosts.map { it.lowercase() }) }
            "js_block_hosts" -> { jsBlockHosts.clear(); jsBlockHosts.addAll(hosts.map { it.lowercase() }) }
        }
    }

    fun isInSet(key: String, host: String): Boolean {
        val h = host.lowercase()
        return loadSet(key).any { h == it || h.endsWith("." + it) }
    }

    fun toggleInSet(key: String, host: String) {
        val h = host.lowercase()
        val cur = loadSet(key)
        if (!cur.add(h)) cur.remove(h)
        setHosts(key, cur)
    }

    fun isOverlayAllowed(host: String): Boolean = overlayAllow.any { host.lowercase() == it || host.lowercase().endsWith("." + it) }
    fun isPopupAllowed(host: String): Boolean = popupAllow.any { host.lowercase() == it || host.lowercase().endsWith("." + it) }
    fun isJsBlockedFor(host: String): Boolean =
        jsBlockEnabled && jsBlockHosts.any { host.lowercase() == it || host.lowercase().endsWith("." + it) }

    fun setPopupMode(all: Boolean) {
        popupBlockAll = all
        prefs?.edit()?.putBoolean("popup_mode_all", all)?.apply()
    }

    // ---------------- 오버레이 제거 JS ----------------

    /**
     * 화면을 가리는 고정 레이어 휴리스틱 제거.
     * 조건: position fixed/sticky + z-index 999+ + 큰 면적 + (광고 키워드 id/class | iframe 포함)
     * 페이지 로드 직후 + 1.5초 + 4초에 반복 (지연 로딩 오버레이 대응)
     */
    fun overlayJs(): String = """
(function(){
  if (window.__jcOverlay) return; window.__jcOverlay = 1;
  var vw = window.innerWidth, vh = window.innerHeight;
  function cls(el){
    var c = el.className;
    return (typeof c === 'string') ? c : ((c && c.baseVal) ? c.baseVal : '');
  }
  function hit(el){
    var st = window.getComputedStyle(el);
    if (st.position !== 'fixed' && st.position !== 'sticky') return false;
    var z = parseInt(st.zIndex || '0'); if (!(z >= 999)) return false;
    var r = el.getBoundingClientRect();
    if (r.width < vw * 0.3 || r.height < vh * 0.15) return false;
    var tag = (el.id || '') + ' ' + cls(el);
    if (/ad|popup|pop_|banner|overlay|sponsor|advert|prm|notice/i.test(tag)) return true;
    if (el.querySelector('iframe')) return true;
    if (r.top < 10 && r.height < vh * 0.5 && /close|dismiss|skip/i.test(el.innerHTML.slice(0, 400))) return true;
    return false;
  }
  function clean(){
    var els = document.querySelectorAll('body *');
    for (var i = 0; i < els.length; i++) { if (hit(els[i])) els[i].remove(); }
  }
  clean();
  setTimeout(clean, 1500);
  setTimeout(clean, 4000);
})();
""".trimIndent()
}
