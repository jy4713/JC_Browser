package com.example.streambrowser.vpn

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * OpenVPN 프로파일 저장소.
 * - 본문: filesDir/vpn/<id>.ovpn
 * - 이름/아이디/인증정보: SharedPreferences "vpn_profiles" (JSON)
 * - 멀티 import 시 파일 이름을 기본 이름으로 사용
 */
object VpnProfiles {

    class Profile(
        val id: String,
        var name: String,
        val file: File,
        /** ovpn에 auth-user-pass 가 있는지 (UI 인증 입력 안내용) */
        val needsAuth: Boolean,
        var username: String = "",
        var password: String = "",
        /** 원본 파일 표시명 (구분용 부제목) */
        val fileName: String = "",
        /** remote 호스트에서 추출한 국가 코드 (예: KR), 없으면 빈 문자열 */
        val country: String = ""
    )

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.getSharedPreferences("vpn_profiles", Context.MODE_PRIVATE)
        dir(ctx).mkdirs()
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "vpn")

    private fun loadMeta(): JSONObject =
        runCatching { JSONObject(prefs?.getString("meta", "{}") ?: "{}") }.getOrElse { JSONObject() }

    private fun saveMeta(o: JSONObject) = prefs?.edit()?.putString("meta", o.toString())?.apply()

    /** ovpn 본문에서 auth-user-pass 직접 사용 여부 (management 쿼리로 대응) */
    fun ovpnNeedsAuth(text: String): Boolean {
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("auth-user-pass", ignoreCase = true)) {
                val rest = line.removePrefix("auth-user-pass").trim()
                // 인라인 <auth-user-pass> 블록이 있으면 자체 인증정보 포함 — 사용자 입력 불필요
                if (rest.isEmpty()) return true
            }
        }
        return false
    }

    fun list(ctx: Context): List<Profile> {
        init(ctx)
        val meta = loadMeta()
        val out = mutableListOf<Profile>()
        val it = meta.keys()
        while (it.hasNext()) {
            val id = it.next()
            val o = meta.optJSONObject(id) ?: continue
            val f = File(dir(ctx), "$id.ovpn")
            if (!f.exists()) continue
            out += Profile(
                id = id,
                name = o.optString("name", id),
                file = f,
                needsAuth = o.optBoolean("needsAuth", false),
                username = o.optString("user", ""),
                password = o.optString("pass", ""),
                fileName = o.optString("file", ""),
                country = o.optString("country", "")
            )
        }
        // 자연 정렬: 숫자 부분은 수치로 비교 (a2 < a10)
        return out.map { repairName(ctx, it) }
            .sortedWith { a, b -> naturalCompare(a.name.lowercase(), b.name.lowercase()) }
    }

    /**
     * 초기 버전에서 SAF lastPathSegment(MediaStore ID, 예: msf:173060)를 이름으로 저장한
     * 프로파일 복구 — remote 호스트 라벨+프로토콜로 이름을 다시 만들고 국가 코드를 채운다.
     */
    private fun repairName(ctx: Context, p: Profile): Profile {
        if (!p.name.matches(Regex("msf:\\d+"))) return p
        val text = runCatching { p.file.readText() }.getOrDefault("")
        if (text.isBlank()) return p
        val host = Regex("(?im)^\\s*remote\\s+(\\S+)").find(text)?.groupValues?.get(1) ?: return p
        val label = host.substringBefore('.')
        val proto = Regex("(?im)^\\s*proto\\s+(tcp|udp)").find(text)?.groupValues?.get(1)?.lowercase()
            ?: Regex("(?im)^\\s*remote\\s+\\S+\\s+\\d+\\s+(tcp|udp)").find(text)?.groupValues?.get(1)?.lowercase()
            ?: ""
        val newName = if (proto.isNotEmpty()) "$label-$proto" else label
        val country = detectCountry(text)
        val meta = loadMeta()
        meta.optJSONObject(p.id)?.put("name", newName)?.put("country", country)?.also { saveMeta(meta) }
        return Profile(p.id, newName, p.file, p.needsAuth, p.username, p.password, p.fileName, country)
    }

    /** 숫자 인식 자연 비교 (msf:2 < msf:10) */
    private fun naturalCompare(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var si = i; while (si < a.length && a[si].isDigit()) si++
                var sj = j; while (sj < b.length && b[sj].isDigit()) sj++
                val na = a.substring(i, si).toLongOrNull() ?: 0
                val nb = b.substring(j, sj).toLongOrNull() ?: 0
                if (na != nb) return na.compareTo(nb)
                i = si; j = sj
            } else {
                if (a[i] != b[j]) return a[i].compareTo(b[j])
                i++; j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    /** SAF Uri 의 실제 표시명 조회 (lastPathSegment 가 msf:123 같은 MediaStore ID 일 때 대비) */
    fun displayNameOf(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    /** remote 호스트의 첫 라벨에서 국가 코드 추출 (예: kr-seo.prod.x.com → KR) */
    fun detectCountry(text: String): String {
        for (raw in text.lines()) {
            val line = raw.trim()
            if (!line.startsWith("remote ", ignoreCase = true)) continue
            val host = line.removePrefix("remote").trim().split(Regex("\\s+")).firstOrNull() ?: continue
            val code = host.substringBefore('.').substringBefore('-').uppercase()
            if (code.length == 2 && code.all { it in 'A'..'Z' }) return code
        }
        return ""
    }

    fun get(ctx: Context, id: String): Profile? = list(ctx).firstOrNull { it.id == id }

    /** 단일/멀티 공통 추가. uri 에서 본문 읽고 기본 이름 반환, 저장된 프로파일 id 반환 */
    fun import(ctx: Context, uri: Uri, defaultName: String?): Profile? {
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return null
        if (text.isBlank()) return null
        val disp = displayNameOf(ctx, uri)
        val name = defaultName?.trim().takeUnless { it.isNullOrEmpty() }
            ?: disp?.removeSuffix(".ovpn")
            ?: runCatching { uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".ovpn") }.getOrNull()
        return importText(ctx, text, name, disp)
    }

    /** ovpn 본문 텍스트로 직접 추가 (메뉴에서 직접 입력 시) */
    fun importText(ctx: Context, text: String, defaultName: String?, fileName: String? = null): Profile? {
        init(ctx)
        if (text.isBlank()) return null
        val name = defaultName?.trim().takeUnless { it.isNullOrEmpty() }
            ?: "VPN ${System.currentTimeMillis()}"
        val id = UUID.randomUUID().toString().substring(0, 8)
        val f = File(dir(ctx), "$id.ovpn")
        f.writeText(sanitize(text))
        val meta = loadMeta()
        meta.put(id, JSONObject()
            .put("name", name)
            .put("needsAuth", ovpnNeedsAuth(text))
            .put("file", fileName ?: "")
            .put("country", detectCountry(text)))
        saveMeta(meta)
        return get(ctx, id)
    }

    /** 사용자 config 정리: management 관련 지시는 우리가 관리하므로 제거 */
    private fun sanitize(text: String): String {
        val sb = StringBuilder()
        for (raw in text.lines()) {
            val t = raw.trim()
            if (t.startsWith("management ", ignoreCase = true)) continue
            if (t.startsWith("askpass", ignoreCase = true)) continue
            // auth-user-pass <file> 은 존재하지 않는 파일 참조 시 실패 — bare 로 바꿔 mgmt 쿼리 유도
            if (t.startsWith("auth-user-pass ", ignoreCase = true)) {
                sb.appendLine("auth-user-pass")
                continue
            }
            sb.appendLine(raw)
        }
        return sb.toString()
    }

    fun rename(ctx: Context, id: String, newName: String) {
        init(ctx)
        val meta = loadMeta()
        meta.optJSONObject(id)?.put("name", newName.trim())
        saveMeta(meta)
    }

    fun saveAuth(ctx: Context, id: String, user: String, pass: String) {
        init(ctx)
        val meta = loadMeta()
        meta.optJSONObject(id)?.put("user", user)?.put("pass", pass)
        saveMeta(meta)
    }

    fun delete(ctx: Context, id: String) {
        init(ctx)
        File(dir(ctx), "$id.ovpn").delete()
        val meta = loadMeta()
        meta.remove(id)
        saveMeta(meta)
    }
}
