# JC Browser 프로젝트

스트리밍/미디어 다운로드 중심 안드로이드 브라우저 (WebView 기반, Soul/TinCat 스타일 UI)

- 저장소: https://github.com/jy4713/JC_Browser
- 작업 디렉터리: `C:\Temp\workspace\JC_Browser\stream-browser`
- 현재 버전: 2.4.0 (versionCode 28)

## 이슈 트래커

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
