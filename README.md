# JC Browser

WebView 기반 안드로이드 브라우저 — 광고 차단, 스트리밍 영상/이미지 감지·다운로드, Chrome/Brave 스타일의 안정성 기능을 갖춘 **완전 오리지널 코드** 프로젝트입니다. (상용 앱 코드 복제 없음)

## 주요 기능

### 브라우저
- 멀티 탭 / 시크릿 탭 (링크 팝업은 새 탭으로 열림, 링크로 연 탭에서 뒤로가기 시 탭 닫고 복귀)
- 탭 하단 시트: 도메인 아바타, 제목/URL, 개별 닫기 · 주소창 좌우 스와이프로 탭 전환
- 시작 시 탭 자동 복원 · 페이지 내 찾기 · 데스크톱 모드 · 글자 크기 조절
- 링크/이미지 롱프레스 메뉴 (새 탭/시크릿 탭/복사/공유/이미지 저장)
- 풀스크린 영상 재생 (전체화면 버튼 지원)

### 안정성 (Chrome/Chromium 분석 기반)
- **Safe Browsing** — 피싱/악성코드 사이트 탐지 시 자동 차단 (WebView 내장)
- **렌더 크래시 자동 복구** — 렌더 프로세스 종료 시 같은 URL로 탭 복구, 앱 크래시 방지
- 렌더러 우선순위 정책 (포그라운드 유지 / 백그라운드 해제)
- WebView 공급자 오류 감지 및 안내 화면
- 방문 기록 자동 정리 (최신 3,000걸 유지)

### 광고 차단
- `shouldInterceptRequest()`에서 광고/트래커 도메인 차단 (호스트 + 서브도메인 매치)
- 차단 리스트: `app/src/main/assets/` (EasyList 계열 오픈 표준 기반), 메뉴에서 ON/OFF

### 미디어 감지 + 다운로드
- 페이지 이동 시 목록 자동 리셋 → 현재 페이지의 미디어만 표시
- `.m3u8`(HLS), `.mpd`(DASH), `.mp4`, `.webm`, `.flv` 자동 감지 + 페이지 내 `video/audio/img` 스캔
- 받기 전 이름/확장자 지정 (기본값 자동 추천, 수정 가능) · ▶ 미리보기
- FFmpegKit으로 HLS 세그먼트 합본 → 단일 파일 (스트림 복사, 화질 손실 없음)
- 이미지(300px 이상) 감지 및 다운로드
- 다운로드 관리 화면: 진행률 %/MB, 취소, 재생, 이름 변경, 삭제

### 토렌트 (libtorrent4j)
- magnet / `.torrent` 링크 자동 가로채기
- 설정 ON: **받으면서 재생** — 영상 파일만 순차 다운로드(sequential pieces) 우선순위 지정 후 플레이어에서 즉시 재생 (MKV/WebM 권장, MP4는 moov 위치에 따라 못 여는 경우 있음)
- 설정 OFF: `.torrent`를 일반 파일로 다운로드
- TorrentActivity: 진행률 %, 시드/피어 수, 수신량 실시간 표시, 전체 다운로드 시 Downloads/JC Browser 납품

### UI / 테마
- 다크 모드 설정 (dark / light / system), values-night 리소스 전면 대응
- 탭 개수 배지 (숫자만), 메뉴 시트·탭 시트 테마 색상, 화면 아래로 당기기 새로고침
- PIP (동영상 재생 중 홈 버튼), 팝업 차단 ON/OFF 토글
- 쿠키·서드파티 쿠키 허용 (구글 동의 등 세션 유지)

### 데이터
- 즐겨찾기: 폴터(하위 디렉토리) 생성/이동, HTML 가져오기·낳볶기 (Netscape 포맷 — Chrome/Soul 호환)
- 방문 기록: 검색 필터, 도메인 아바타
- 설정: 어두운 모드, 데스크톱 모드, 탭 복원, 광고 차단

## 프로젝트 구조

```
app/src/main/java/com/example/streambrowser/
├── MainActivity.kt               # 메인 UI, 탭 관리, 풀스크린, 롱프레스 메뉴
├── browser/
│   ├── AdBlocker.kt              # 광고 차단 엔진
│   ├── SniffingWebViewClient.kt  # 요청 가로채기: 차단 + 스트림 감지 + Safe Browsing
│   ├── VideoJsBridge.kt          # 페이지 주입 스캐너 JS (video/audio/img/XHR 수집)
│   └── DetectedVideo.kt          # 감지 미디어 모델 + 스토어
├── download/
│   ├── DownloadStore.kt          # 다운로드 상태 저장소
│   ├── VideoDownloadService.kt   # FFmpegKit 포그라운드 다운로드 (%/크기 계산, 취소)
│   ├── ImageDownloader.kt        # 이미지 직접 다운로드
│   └── VideoAdapter.kt           # 감지 미디어 목록 (영상+이미지)
├── db/
│   └── BrowserDb.kt              # SQLite (즐겨찾기 v2: 폴터 지원, 기록)
├── torrent/
│   └── TorrentManager.kt         # libtorrent4j 세션, 순차 다운로드 우선순위, 파일 준비 대기
└── ui/
    ├── BookmarksActivity.kt      # 즐겨찾기 (폴터, HTML 임포트/익스포트)
    ├── HistoryActivity.kt        # 방문 기록
    ├── DownloadsActivity.kt      # 다운로드 관리
    ├── PlayerActivity.kt         # 비디오 플레이어 (http/https + file:// 로컬 재생)
    └── TorrentActivity.kt        # 토렌트 진행 화면 (재생/전체 다운로드/중지)
```

## 빌드 방법

```
set JAVA_HOME=<JDK 17 경로>
set ANDROID_HOME=<Android SDK 경로>
gradle assembleDebug
```

- compileSdk 34, minSdk 24, Kotlin, AGP 8.5.2, Gradle 8.9
- FFmpegKit 5.1 (full-gpl) — Maven Central에서 제거되어 Aliyun 미러 사용 (settings.gradle.kts 참조)
- libtorrent4j 2.1.0 (arm64 / armeabi-v7a 네이티브 포함 — universal APK ~150MB, arm64 전용 ~45MB)
- 출력: `app/build/outputs/apk/debug/app-debug.apk`

## 버전 히스토리

- **v2.6.0** — 토렌트 순차 재생 (받으면서 재생, ON/OFF 설정), 쿠키 서드파티 허용 (구글 동의 반복 수정), 다크모드 메뉴 시트 대응, 탭 배지 스타일
- **v2.5.x** — 다크모드 글자색 전면 대응, 팝업 차단 메뉴 토글, KOR Site list URL 수정, PIP / pull-to-refresh, 주소창 포커스 동작 수정, 메뉴 재구성

## 로드맵 (아이디어)

- Brave식 EasyList 필터 + 요소 숨김 (빈칸 제거)
- 탭 그룹, 뒤로가기 길게 누르기 = 방문 기록 팝업
- 사이트별 설정 (권한/데스크톱 기억), 토렌트 커스텀 트래커 목록

## 참고

- 스트리밍 다운로드는 **저작권 보호 대상 콘텐츠에 사용하면 불법**일 수 있습니다. 본인이 소유하거나 이용이 허락된 콘텐츠에만 사용하세요.
- 기본적으로 디버그 서명 빌드입니다. 출시 시 `assembleRelease` + 서명 키가 필요합니다.
