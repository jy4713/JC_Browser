package com.example.streambrowser.util

import android.content.Context
import android.content.SharedPreferences
import com.example.streambrowser.db.BookmarkRepo
import com.example.streambrowser.vpn.VpnProfiles
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 설정/즐겨찾기/VPN 프로파일 통합 백업·복원.
 *
 * 파일 형식 (이진):
 *   "JCBK1" (5B) | salt(16B) | iv(12B) | AES-256/GCM 암호문
 * 키는 사용자 백업 비밀번호에서 PBKDF2-HmacSHA256(120k) 로 유도 —
 * 비밀번호 없이는 누구도 파일을 열 수 없고, 복원도 앱 안에서만 가능.
 *
 * 범위(scope): settings / bookmarks / vpn — 백업·복원 시 선택적으로 포함.
 */
object BackupManager {

    const val SCOPE_SETTINGS = "settings"
    const val SCOPE_BOOKMARKS = "bookmarks"
    const val SCOPE_VPN = "vpn"

    private const val MAGIC = "JCBK1"
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val PBKDF_ITER = 120_000

    /** 앱 세션/기기 상태용 — 백업에서 제외하는 설정 키 */
    private val TRANSIENT_KEYS = setOf("saved_tabs", "saved_index", "vpn_active_id")

    class BackupException(msg: String) : Exception(msg)

    // ---------------- 백업 생성 ----------------

    fun buildBackup(ctx: Context, scopes: Set<String>): ByteArray {
        val root = JSONObject()
            .put("app", "JC Browser")
            .put("format", 1)
            .put("time", System.currentTimeMillis())
        root.put("scopes", scopes.fold(JSONObject()) { o, s -> o.put(s, true) })

        if (SCOPE_SETTINGS in scopes) {
            val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val settings = JSONObject()
            for ((k, v) in sp.all) {
                if (k in TRANSIENT_KEYS) continue
                when (v) {
                    is Boolean -> settings.put(k, v)
                    is Int -> settings.put(k, v)
                    is Long -> settings.put(k, v)
                    is Float -> settings.put(k, v.toDouble())
                    is String -> settings.put(k, v)
                    // Set<String> 등 이외 타입은 건러냄
                }
            }
            root.put("settings", settings)
        }
        if (SCOPE_BOOKMARKS in scopes) {
            root.put("bookmarks", BookmarkRepo.exportTree(ctx))
        }
        if (SCOPE_VPN in scopes) {
            root.put("vpn", VpnProfiles.exportAll(ctx))
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    /** 백업 본문을 비밀번호로 암호화. 반환 바이너리는 헤더(salt/iv) 포함 완성 파일 */
    fun encrypt(plain: ByteArray, password: String): ByteArray {
        if (password.length < 4) throw BackupException("password too short")
        val rand = SecureRandom()
        val salt = ByteArray(SALT_LEN).also(rand::nextBytes)
        val iv = ByteArray(IV_LEN).also(rand::nextBytes)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plain)
        return MAGIC.toByteArray(Charsets.US_ASCII) + salt + iv + ct
    }

    /** 암호 해제. 비밀번호 불일치/파일 손상 시 BackupException */
    fun decrypt(file: ByteArray, password: String): ByteArray {
        val magic = MAGIC.toByteArray(Charsets.US_ASCII)
        if (file.size < magic.size + SALT_LEN + IV_LEN + TAG_BITS / 8 ||
            !file.copyOfRange(0, magic.size).contentEquals(magic)
        ) throw BackupException("not a JC Browser backup file")
        var off = magic.size
        val salt = file.copyOfRange(off, off + SALT_LEN).also { off += SALT_LEN }
        val iv = file.copyOfRange(off, off + IV_LEN).also { off += IV_LEN }
        val key = deriveKey(password, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(file.copyOfRange(off, file.size))
        } catch (e: Exception) {
            throw BackupException("wrong password or corrupted file")
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF_ITER, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    // ---------------- 복원 ----------------

    /** 파일 안에 실제로 들어있는 범위 목록 (복원 다이얼로그 체크박스 활성화용) */
    fun scopesIn(plain: ByteArray): Set<String> {
        val root = JSONObject(String(plain, Charsets.UTF_8))
        val scopes = root.optJSONObject("scopes") ?: JSONObject()
        val out = mutableSetOf<String>()
        listOf(SCOPE_SETTINGS, SCOPE_BOOKMARKS, SCOPE_VPN).forEach { if (scopes.optBoolean(it)) out += it }
        return out
    }

    /**
     * 복원 적용. replaceBookmarks=true 면 기존 즐겨찾기를 전부 지우고 백업 내용으로 교체.
     * 설정 복원 후 즉시 반영이 필요한 모듈 값도 함께 갱신한다.
     */
    fun applyRestore(ctx: Context, plain: ByteArray, scopes: Set<String>, replaceBookmarks: Boolean) {
        val root = JSONObject(String(plain, Charsets.UTF_8))

        if (SCOPE_SETTINGS in scopes && root.has("settings")) {
            val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val ed = sp.edit()
            root.getJSONObject("settings").keys().forEach { k ->
                when (val v = root.getJSONObject("settings").get(k)) {
                    is Boolean -> ed.putBoolean(k, v)
                    is Int -> ed.putInt(k, v)
                    is Long -> ed.putLong(k, v)
                    is Double -> ed.putFloat(k, v.toFloat())
                    is String -> ed.putString(k, v)
                }
            }
            ed.apply()
            // 앱 내 캐시된 설정값 즉시 반영
            com.example.streambrowser.browser.WebCleaner.init(ctx)
            com.example.streambrowser.browser.AdBlocker.reload()
        }

        if (SCOPE_BOOKMARKS in scopes && root.has("bookmarks")) {
            if (replaceBookmarks) BookmarkRepo.clearAll(ctx)
            BookmarkRepo.importTree(ctx, root.getJSONArray("bookmarks"))
        }

        if (SCOPE_VPN in scopes && root.has("vpn")) {
            VpnProfiles.replaceAll(ctx, root.getJSONArray("vpn"))
        }
    }
}
