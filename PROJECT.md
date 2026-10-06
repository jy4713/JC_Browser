# JC Browser 프로젝트

스트리밍/미디어 다운로드 중심 안드로이드 브라우저 (WebView 기반, Soul/TinCat 스타일 UI)

- 저장소: https://github.com/jy4713/JC_Browser
- 작업 디렉터리: `C:\Temp\workspace\JC_Browser\stream-browser`
- 현재 버전: 2.8.0 (versionCode 42)

## 이슈 트래커

### 완료 (2026-10-06, v2.8.0)
- [x] 토렌트 전면 개편: "받으면서 재생" 제거 → 전체 파일 다운로드 방식. .torrent/magnet은 토렌트 지원 ON일 때만 공유 파일 자동 다운로드 (OFF면 .torrent만 일반 다운로드, magnet은 켜기 안내)
- [x] 토렌트 다운로드 관리 화면 신규 (메뉴 → 다운로드 설정 → 토렌트 다운로드): 진행 중/완료/취소·실패 필터, 진행률+다운·업로드 속도+시드/피어 표시, 일시 중지/재시작/취소, 완료 항목 실행(영상은 내장 플레이어), 삭제 (취소·실패 항목은 받던 파일도 함께 삭제)
- [x] 토렌트 설정 추가: 동시 다운로드 개수 (1~5), 다운/업로드 속도 제한 (KB/s, 0=무제한, libtorrent SettingsPack), 완료 파일은 기존 "다운로드 위치" 설정 폴터로 납품
- [x] HLS 재생 실패 대응: hls.js FATAL 또는 8초 무응답 시 네이티브 재생으로 자동 폴드백 + 에러 오버레이에 사유 표시 유지
- [x] 톱니(⚙) 플로팅 버튼: video 태그 존재 시 항상 표시 → 전체화면에서 동영상 길게 누르기 시에만 2초간 표시 (Soul 스타일, 설정 안 하면 자동 숨김). 비전체화면 길게누르기는 기존처럼 바로 메뉴
- [x] 프린트 실패 시 무인자 PrintDocumentAdapter로 재시도 + 실패 토스트
- [x] 일반 파일 다운로드 화면: 다운로드 관리에 "파일" 탭 추가 (시스템 다운로드 매니저의 zip/pdf 등 조회, 열기/삭제)
- [x] 앱 아이콘: 파랑 그라데이션 둥근 사각 + 흰색 "JC" (adaptive foreground/background 포함)

### 완료 (2026-10-06, v2.4.0)
- [x] 9mod.com 광고차단/팝업차단 미동작 → 기본 필터에 AdGuard Base + EasyList 추가, 기존 사용자에게도 누락분 자동 반영
- [x] 주소창 엔터 시 키보드 안 날아감 → loadUrl()에서 IME 숨기기 + 포커스 해제
- [x] Cloudflare "Performing security verification" 체크 미표시 → 보안 인증 페이지는 스캐너 JS 주입/오버레이 차단/요소 숨김/광고차단 전부 제외 (challenges.cloudflare.com, hcaptcha.com, recaptcha.net)
- [x] PIP 동영상 재생 중에만 동작 → 수동 PIP 버튼은 재생 확인 후 진입, 자동 PIP도 재생 중일 때만
- [x] 아래로 당겨서 새로고침 (PullRefreshLayout 추가)

### 완료 (이전)
- [x] v2.3.7 한국어 문자열 깨짐 교정, 탭 시트 정리, About 정리, 아이콘 파스텔 톤
- [x] v2.3.6 다운로드 일시 중지/이어받기, 필터 업데이트 피드백
- [x] v2.3.5 탭 시트 모든 탭 닫기, 하단 버튼 볍더리스
- [x] v2.3.4 SSL 오류 사용자 확인(보안), 광고차단 성능 최적화
- [x] v2.3.3 다운로드 목록 URL 복사/삭제 버튼, 주소창 높이 축소

## 규칙
- 정상 동작하는 기능은 건드리지 않는다
- 한국어/영어 문자열은 values-ko / values 모두 유지 (터미널 한글 깨짐 주의 — 코드포인트 사용)
- 빌드: `gradle.bat --no-daemon assembleDebug`, 배포: C:\Temp\workspace\ + Downloads 양쪽 복사
