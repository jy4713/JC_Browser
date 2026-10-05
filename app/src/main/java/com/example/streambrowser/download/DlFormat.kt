package com.example.streambrowser.download

/** 다운로드 진행 정보 공통 포맷 (리스트 버튼 / 노티피케이션 / 다운로드 화면 3곳 동일) */
object DlFormat {

    /**
     * 진행 중인 항목 한 줄 표기.
     * - 분할 다운로드: totalDurationMs = 전체 바이트, currentTimeMs = 받은 바이트 → "12.3/45.6 MB · 1.2 MB/s"
     * - HLS: totalDurationMs = 세그먼트 총수, currentTimeMs = 받은 세그먼트 수 → "34% · 12.3 MB · 1.2 MB/s"
     * - 합계 불명: "12.3 MB · 1.2 MB/s"
     */
    fun progress(item: DlItem): String {
        val mb = item.doneBytes / 1048576.0
        val spd = item.speedBps / 1048576.0
        return when {
            item.totalDurationMs > 500_000 && item.currentTimeMs > 0 -> {
                val cur = item.currentTimeMs / 1048576.0
                val total = item.totalDurationMs / 1048576.0
                String.format("%.1f/%.1f MB · %.1f MB/s", cur, total, spd)
            }
            item.totalDurationMs > 0 && item.currentTimeMs > 0 -> {
                val p = (item.currentTimeMs * 100 / item.totalDurationMs).coerceIn(0, 100)
                String.format("%d%% · %.1f MB · %.1f MB/s", p, mb, spd)
            }
            else -> String.format("%.1f MB · %.1f MB/s", mb, spd)
        }
    }
}
