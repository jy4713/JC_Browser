# JC Browser 프로젝트

스트리밍/미디어 다운로드 중심 안드로이드 브라우저 (WebView 기반, Soul/TinCat 스타일 UI)

- 저장소: https://github.com/jy4713/JC_Browser
- 작업 디렉터리: `C:\Temp\workspace\JC_Browser\stream-browser`
- 현재 버전: 2.11.25 (versionCode 79)

## 이슈 트래커

#### 완료 (2026-10-10, v2.11.25)
- [x] **스트리밍 감지 미동작 추적용 전면 로깅** — 사용자 기기 로그(`adb logcat -s JC_Sniff:V`)에서 iframe(jwplayer/hls.js)이 정상 로딩·재생 중(`frameRate=29.97`)인데 m3u8 흔적과 JC_Sniff 로그가 0건으로, 주입 시도 자체 여부를 알 수 없던 문제. `injectScanner` 진입/성공(`inject OK`)/스킵 사유(챌린지 호스트·페이지·네이버)/예외, 네이티브 미디어 감지(`media`), JS 스캐너의 `addVideo` 콜백, 문서 판정 실패 메인프레임(`mainFrame not doc`)까지 전부 Logcat 남김. 앱 시작 시 `JC Browser x.y.z (code)` 시작 로그로 설치 버전 확인 가능. BuildConfig 접근용 `buildFeatures.buildConfig = true` 활성화

#### 완료 (2026-10-10, v2.11.24)
- [x] **스트리밍 감지 실패 근본 원인 수정 — iframe 스캐너 주입 시 Referer 보충** — 사이트 분석 결과: 영상은 크로스오리진 iframe(호스트가 수시로 바뀌는 영상 호스팅, 예: FirePlayer/JWPlayer) 안에서 hls.js 가 m3u8 을 XHR 로 받아가는 구조. WebView 의 `shouldInterceptRequest` 는 요청 헤더에서 **Referer 를 빼고** 낚는 경우가 많아(Chromium), 스캐너 주입용 iframe HTML 재요청이 리퍼러 없이 나가 서버가 404 를 돌림 → 폴드백(원본 로딩)으로 스캐너 없이 로드돼 재생은 되는데 다운로드 목록이 텅 빔(실제 curl 로 리퍼러 유무에 따라 404/200 확인). 수정: 주입 재요청 시 복사된 헤더에 Referer 가 없고 메인 프레임이 아니면 `view.url`(부모 페이지)을 Referer 로 보충. 주입 실패/비-HTML 응답 시 Logcat(`JC_Sniff`) 경고 남김
- [x] Soul Browser 감지 방식 분석 참고 — Soul 도 shouldInterceptRequest 에서 URL 패턴(m3u8/mpd 중첩 URL·Range 헤더·tsfake/m3fake 등)으로 네이티브 등록 + 별도 JS 주입 병용. 우리는 본문 기반 판별(v2.11.23)과 Referer 보충으로 iframe 커버를 완성하는 방향 유지

#### 완료 (2026-10-10, v2.11.23)
- [x] **스트리밍(HLS) 감지 근본 보강 — 스캐너 JS 본문 기반 판별** — XHR/fetch 훅에서 응답 본문에 `#EXTM3U`(또는 `<MPD`)가 있으면 URL 확장자/토큰과 무관하게 재생목록으로 등록 (확장자 없는 `?token=` 경로·실제로 m3u8 을 날리는 API 경로까지 포착), fetch content-type 조건에 `mpegurl` 추가, addVideo 에 종류 강제 파라미터(HLS/DASH) 추가
- [x] **스캐너 주기적 재주입** — 페이지 완료 후 5초 간격 evaluateJavascript 재주입(`__sbScanner` 가드로 중복 실행 없음). SPA/늦게 로드되는 프레임 누락과 CSP 차단 케이스 대비. 주입 프리픽스(이미지 최소 폭/사이트별 배속/탭메뉴 설정)는 매번 갱신
- [x] **보안 인증 페이지 판정 완화** — `isSecurityChallengePage` 의 recaptcha 는 실제 위젯 사용(`g-recaptcha`/`grecaptcha`)일 때만 스캐너 주입 스킵. 플레이어 HTML 이 recaptcha api.js 만 참조하던 경우까지 제외돼 iframe 플레이어에 스캐너가 안 들어가 스트리밍 감지가 빠지던 문제
- [x] **네이티브 전체화면 롱프레스 복구 (터치 래퍼)** — 커스텀 뷰를 `dispatchTouchEvent` 오버라이드 익명 FrameLayout 래퍼로 감쌈(자식 비디오 서피스가 터치를 소비핸 기존 OnTouchListener 는 부모에 안 닿음). 600ms 유지 → 톱니 버튼, 설정(일반 → 화면 탭으로 동영상 메뉴 열기, 기본 OFF) ON 시 짧은 탭으로도 메뉴
- [x] **전체화면 제스처 (JS 풀스크린 모드 한정)** — 투명 오버레이: 좌측 40% 세로 드래그 밝기 / 우측 40% 음량 / 가로 드래그 시크(전체 폭=90초, 시간 오버레이 표시) / 양쪽 더블탭 ±10초 / 톱니 메뉴의 터치 잠금(화면 잠금 중엔 터치 무시, 자물쇠 버튼으로 해제). 밝기·음량은 네이티브(윈도우 속성/AudioManager) 연결. 사이트 자체 전체화면(네이티브 커스텀 뷰)에는 적용 불가 — 메뉴·롱프레스만 동작
- [x] **동영상 메뉴: 이전/다음 화 + 사이트별 배속 저장** — URL 마지막 숫자 ±1 회차 이동 행(숫자 없거나 0 이하면 미표시), 배속 선택 시 `speed_<호스트>` 로 저장해 같은 사이트 재생 시 자동 적용
- [x] **동영상 다운로드 대기열** — 설정(다운로드 설정 → 동시에 받을 동영상 수, 1~4, 기본 2) 초과 건은 서비스 내 대기열에 적재, 완료 시 순서대로 시작. 대기 토스트(`notif_queued`) 표시
- [x] **플레이어 자막 (SRT/VTT)** — 로컬 파일 재생 시 같은 폴터의 같은 이름 .srt/.vtt 탐색 → 타임드 오버레이 표시 (SRT/VTT 공통 파서, UTF-8 아니면 EUC-KR 폴드백, HTML 태그 제거, `</script>` 이스케이프)

#### 완료 (2026-10-10, v2.11.22)
- [x] **동영상 감지 누락 수정 (스트리밍 사이트 다운로드 안 됨)** — 원인 2개: ① shouldInterceptRequest 의 광고 차단 판정이 미디어 감지보다 먼저 실행돼, 차단 필터에 걸린 스트리밍 CDN(m3u8) 요청이 빈 응답으로 바뀌어 재생·다운로드가 둘 다 먹통이 되는 사례 → 미디어로 판별된 요청은 광고 차단·이미지 차단에서 무조건 제외하고 목록에만 등록하도록 순서 교체 (uBO 동일 정책) ② 일부 플레이어가 `/playlist/master` 같이 확장자 없는 경로에 토큰만 쿼리로 붙이는 URL 을 쓰면 기존 `.m3u8` 포함 검사로는 감지 불가 → 네이티브 detect() 는 `m3u8` 경로 어디든 허용, 페이지 스캐너 JS(reportUrl/scanText)도 m3u8 토큰 기반으로 확대
- [x] **전체화면 롱프레스 톱니 메뉴 복구 + Soul 스타일 메뉴 정비** — ① 커스텀 뷰(네이티브 전체화면)의 setOnLongClickListener 는 비디오 서피스가 터치를 소비해 안 먹는 경우가 많아 터치 유지 600ms 감지(OnTouchListener)로 교체 ② 동영상 메뉴 최상단에 [동영상 다운로드] 추가 — 현재 탭 감지 목록에서 다운로드 가능한 것(HLS/MP4 최신 우선)을 그대로 큐에 등록 (VideoAdapter.enqueue companion 추가) ③ 방향 메뉴(자동 회전/가로/세로)는 기존 유지
- [x] **입력 다이얼로그 좌우 여백** — setView(EditText) 를 그대로 넣어 대화상자 가장자리에 딱 붙던 입력창(새 폴더 이름, 홈 주소, 토렌트 URL 열기, 백업 비밀번호, 즐겨찾기 폴더/이름, 다운로드 이름 변경)을 JcUi.fieldBox() 래퍼(좌우 20dp 여백)로 통일. 이미 박스+여백이 있던 다이얼로그(VPN 계열/인증/토렌트 속도/파일명+확장자 등)는 그대로 유지

#### 완료 (2026-10-10, v2.11.21)
- [x] **백업 / 복원 기능 신규** — 메뉴 [일반] → [백업 / 복원]. 백업·복원 시 범위를 설정/즐겨찾기/VPN 프로파일에서 체크박스로 선택
  - **저장 위치**: 클라우드(Google Drive / OneDrive 등 SAF 문서 선택기로 직접 지정) 또는 로컬(Download/JC Browser 폴더, MediaStore. API 28 이하는 WRITE_EXTERNAL_STORAGE(maxSdk 28 선언) 요청 후 직접 쓰기)
  - **암호화**: 백업 시 사용자가 설정한 비밀번호(4자+)로 PBKDF2-HmacSHA256(12만 회) → AES-256/GCM 암호화. 파일 형식 JCBK1 헤더+salt+iv+암호문 — 비밀번호 없이는 열 수 없고 복원도 앱 안에서만 가능
  - **백업 내용**: 설정(settings 프리퍼런스 전체, saved_tabs/saved_index/vpn_active_id 등 세션 상태키 제외), 즐겨찾기(폴더 구조 유지 JSON 트리), VPN 프로파일(ovpn 본문+이름+국가+아이디/비밀번호 포함)
  - **복원**: 파일 선택 → 비밀번호 확인 → 파일에 들어있는 범위만 활성화된 체크박스 → 적용 후 recreate(). 즐겨찾기는 기존 내용 삭제 후 교체, VPN 프로파일은 전체 교체, 설정은 WebCleaner/AdBlocker 캐시 즉시 갱신
  - 신규 파일: util/BackupManager.kt (암호화/수집/적용), VpnProfiles.exportAll/replaceAll, BookmarkRepo.exportTree/importTree/clearAll 추가. 문자열 22개 ko/en 추가

#### 완료 (2026-10-10, v2.11.20)
- [x] **데스크톱(UA) 모드 기본 OFF로 되돌림** — v2.11.19 의 기본 ON 을 모바일 기본으로 재변경 (WebView 생성/메뉴 토글/설정 초기화 4곳). 필요 시 메뉴에서 수동 전환 가능
- [x] **사이트 파일 업로드(input type=file) 지원 추가** — 기존엔 WebChromeClient 에 onShowFileChooser 가 없어 사이트의 파일 업로드 버튼이 묵살. ACTION_OPEN_DOCUMENT 파일 선택기 + 기존 onActivityResult(REQ 5) 로 결과를 ValueCallback 에 전달 (취소 시 null)
- [x] **위치 정보 권한 요청 지원 추가** — onGeolocationPermissionsShowPrompt 구현 (기존 무응답 → 사이트별 허용/거부 다이얼로그, origin 의 사이트명 표시)
- [x] **WebRTC 카메라/마이크 권한 요청 지원 추가** — onPermissionRequest 구현. RESOURCE_VIDEO_CAPTURE/AUDIO_CAPTURE 만 허용/거부 다이얼로그, 그 외 리소스는 자동 거부. 메니페스트에 CAMERA/RECORD_AUDIO/ACCESS_FINE/COARSE_LOCATION 권한 4개 선언 추가
- [x] **전체 검증: 언어(한/영) 적용 상태** — 액티비티 10개 전부 attachBaseContext 에 LocaleHelper.wrap + onCreate 에 ThemeHelper.apply 확인, strings ko/en 키 일치 확인 (신규 6키 ko/en 동시 추가 — 위치/미디어 권한 안내, 허용/거부)
- [x] **브라우저 기본 동작 점검** — WebView 설정(javaScript/domStorage/multipleWindows/mixedContent COMPATIBILITY_MODE), 다운로드 리스너, 팝업 차단(onCreateWindow), 전체화면(onShowCustomView), 롱클릭 메뉴, 파일 업로드/위치/WebRTC 추가로 다른 브라우저 수준의 기본 동작 갖춤

#### 완료 (2026-10-10, v2.11.19)
- [x] **VPN CONNECTED 전 트래픽 VPN 경유(흰 페이지) 수정** — WAIT/RECONNECTING 상태에서 기존 tun 이 열린 채로 재시도되어 트래픽이 블랙홀 tun 으로 빨려 들어가 흰 페이지가 나오던 것. WAIT/RECONNECTING 진입 시 tun 을 닫아 일반 네트워크를 그대로 사용 — VPN 경유는 CONNECTED 이후뿐. 새 연결 시작 시 남은 tun 도 선 정리
- [x] **데스크톱(UA) 모드 기본 ON** — 사용자 요청으로 desktop 기본값 false→true (WebView 생성/메뉴 토글/설정 초기화 3곳)
- [x] **WebView 혼합 콘텐츠 정책 크롬/엣지 정합** — https 페이지의 http 리소스를 차단하지 않고 허용 (MIXED_CONTENT_COMPATIBILITY_MODE, 데스크톱 크롬과 동일)
- [x] **다운로드 실패/취소 시 노티바 알림 정리** — 실패 경로(onFailure/FastVideoDownloader 폴스루 포함)에서 최종 알림 갱신이 없어 진행 프로그레스 바가 영구 남던 것. FAILED/CANCELED 는 알림을 cancel, 완료/일시정지만 최종 문구 표시. 서비스 종료 시 stopForeground(STOP_FOREGROUND_REMOVE) 로 잔여 알림 명시 제거
- [x] **네이버 블로그 임베드 iframe 실패 추가 수정** — injectScanner 재요청 시 User-Agent 를 WebView UA 로 명시 (requestHeaders 에 UA 없으면 Java 기본 UA 가 나가 서버가 에러 페이지 반환하던 원인). naver.com 호스트는 문서 재전송 자체를 제외해 네이티브 로딩 유지 (네이버는 스트리밍 스니핑 대상 아님)

#### 완료 (2026-10-10, v2.11.18)
- [x] **공유 링크(goto 리다이렉트) 페이지의 임베드 iframe 실패 수정** — 다른 브라우저는 정상인데 우리 것만 "일시적으로 콘텐츠 정보를 가져올 수 없습니다"가 뜨던 문제. 원인 2개: ① **injectScanner 리다이렉트 쿠키 손실** — 302로 이어지는 문서를 우리가 재요청할 때 HttpURLConnection 자동 리다이렉트가 중간 hop 응답의 Set-Cookie 를 버려 세션이 깨짐 → 리다이렉트를 수동으로 따라가며 각 hop 의 Set-Cookie 를 CookieManager에 저장하도록 재작성 (최대 10 hop) ② **AdBlocker가 iframe 문서(프레임 내비게이션)까지 차단** — 서드파티 규칙에 걸린 임베드 문서가 빈 응답이 되어 프레임 전체가 실패 → Sec-Fetch-Dest가 document/iframe 이거나 Accept에 text/html 인 내비게이션 요청은 차단에서 제외 (uBO 와 동일 — 메인/프레임 문서는 차단하지 않고 그 낶의 리소스만 차단)
- [x] **버튼 모양 변경 (UI만)** — 거의 네모에 코너 라운드 6dp(끝부분만 살짝), 바탕색과 비슷하게 투명도 4%로 더 연하게. JcUi soft() 알파 0x14→0x0A + 라운드 20→6dp, XML soft 드로어블 4종 동일 적용
- [x] **모든 탭 닫기 확인 다이얼로그** — 탭 다이얼로그 [모든 탭 닫기]와 메뉴 [모든 탭 닫기] 둘 다 확인 후 실행 (문자열 close_all_tabs_confirm ko/en 추가)

#### 완료 (2026-10-09, v2.11.17)
- [x] **오버레이 차단 강화** — 켜놔도 오버레이가 보이던 사례(로딩 직후가 아니라 늦게 뜨는 전체화면 딤/모달) 수정. ① 타이밍 패스 1.5초/4초에 8초 추가 ② 20초간 MutationObserver로 DOM 추가 감시 — 늦게 추가되는 레이어도 즉시 제거 ③ 휴리스틱 보강: 화면 80% 이상을 덮는 고정 레이어 + 닫기 수단(close/dismiss/skip/닫기) 있으면 광고 키워드/iframe 없어도 제거 ④ display:none/visibility:hidden 요소 스킵, 빈 영역 스킵으로 오탐 감소
- [x] **버튼 느낌 보강 + 두 버튼 행 균등 폭 (UI만)** — 투명 버튼에 라벨 색 8% 은은한 톤 배경을 입혀 버튼 느낌이 보이도록 통일 (JcUi `soft()` + rippleBg softColor, XML은 bg_btn_soft_blue/green/red/gray 신규). VPN 두 버튼 행(프로파일 관리/로그, 추가/여러 개 가져오기)은 동일 폭으로 양 끝까지 꽉 채움 (weight=1). 탭 다이얼로그(새 탭/모두 닫기), 즐겨찾기(가져오기/내보내기) 에도 soft 배경 + 사이 간격 적용. 기타 라벨 버튼(광고 필터 추가 2곳, 동영상 다운로드, 선택 다운로드, 토렌트 목록)도 soft_blue 적용

#### 완료 (2026-10-09, v2.11.16)
- [x] **전 화면 버튼 스타일 통일 (기능 변경 없음, UI만)** — VPN 화면의 파스텔 알약(채움) 버튼을 탭/즐겨찾기와 동일한 컨셉(투명 배경+물결 ripple, 아이콘+텍스트 13sp)으로 변경. JcUi 공통 키트 재작성: `pill` = 플랫 라벨 버튼(rippleBg 사용), `circleIcon` = 40dp 투명+ripple 아이콘 버튼, 색상을 탭/즐겨찾기 팔레트(#1A73E8/#188038/#D93025/#5F6368/#F9AB00)로 통일. VPN 버튼에 아이콘 추가(프로파일 관리·로그·추가·가져오기·연결·해제), ic_add 벡터 추가. XML 채움 버튼 4곳(bg_btn_primary)도 ripple + primary 텍스트로 변경 — activity_main(선택 다운로드), activity_adfilters(필터 추가/규칙 추가), item_video(다운로드). 전 화면이 bg_btn_ripple 동일 컨셉으로 통일됨

#### 완료 (2026-10-09, v2.11.15)
- [x] **VPN 멀티 import 공통 인증 설정** — .ovpn 파일 여러 개를 한번에 가져올 때 가져온 프로파일마다 일일이 아이디/비밀번호를 넣어야 하던 것을 수정. import 완료 후 "공통 인증 정보 설정" 다이얼로그 표시: 아이디/비밀번호(눈알 표시 포함)를 한 번 입력하면 가져온 프로파일 전체에 적용, 건너뛰기로 개별 설정 유지. 적용/건너뛰기 문자열 한국어/영어 동시 유지

#### 완료 (2026-10-09, v2.11.14)
- [x] **ERR_TOO_MANY_RETRIES (401 인증 재시도 루프) 수정** — 저장된 인증 정보가 틀린 경우 자동 proceed 가 무한 반복되며 Chromium 이 `net::ERR_TOO_MANY_RETRIES` 로 포기하던 문제. 콜백 재진입 = 직전 시도 거절로 간주: 저장 정보는 첫 시도에만 자동 사용, 거절당하면 자동 재시도를 멈추고 입력 창을 다시 표시(기존 값 프리필). 저장 체크 해제 시 해당 호스트 저장 정보 삭제

#### 완료 (2026-10-09, v2.11.13)
- [x] **HTTP 401(BASIC/DIGEST) 인증 입력 창 추가** — 서버가 인증을 요구하는 페이지(로컬 서비스, 공유기 관리 등)에 접속하면 그냥 401 에러 페이지만 보여주던 것을 수정. `onReceivedHttpAuthRequest` 구현: 아이디/비밀번호 입력 다이얼로그 표시 → 확인 시 `handler.proceed()` 로 재시도, 취소/백그라운드 시 취소. "이 기기에 저장" 체크 시 호스트별로 저장(SharedPreferences base64)해 이후 접속은 다이얼로그 없이 자동 인증 (Chrome/Firefox 동작과 동일)

#### 완료 (2026-10-09, v2.11.12)
- [x] **VPN OPENTUN 실패 근본 원인 수정 (치명)** — v2.11.11 진단 로그(`openTun: IFCONFIG 없음`)로 원인 확정: argv의 `--ifconfig-noexec` / `--route-noexec` 플래그가 do_ifconfig()/do_route()를 **아예 건드리지 않게** 만들어, 안드로이드 빌드가 management로 위임하던 IFCONFIG/ROUTE/ROUTE6 NEED-OK 쿼리가 오지 않았던 것. 로컬 IP 없이 OPENTUN만 날아와 tun 생성이 실패했던 흐름. openvpn 소스(init.c `ifconfig_noexec_enabled`/`route_noexec_enabled`)로 검증 후 두 플래그 제거 — 안드로이드(TARGET_ANDROID)는 이 플래그 없이도 시스템 ifconfig/route 명령을 실행하지 않고 전부 management 쿼리로만 처리 (ifconfig_order/route_order 모두 BEFORE_TUN 이라 OPENTUN 전에 필요 정보 수집 완료)

#### 완료 (2026-10-09, v2.11.11)
- [x] **sendFd 숨은 API 제거 + openTun 예외 로깅** — OPENTUN fd 전송에서 `FileDescriptor.setInt$` 리플렉션(숨은 API, 기기별 차단 가능)을 제거하고 `ParcelFileDescriptor.fileDescriptor`를 SCM_RIGHTS로 직접 전달하도록 단순화. openTun의 runCatching이 삼키던 예외와 establish() null 원인(IFCONFIG 누락 여부)을 로그 콘솔에 기록 — 다음 테스트에서 실패 지점이 정확히 구분됨

#### 완료 (2026-10-09, v2.11.10)
- [x] **OPENTUN 실패 진단 구분** — 로그상 PERSIST_TUN_ACTION까지 통과 후 OPENTUN에서 "VPN 인터페이스 생성 실패"로 종료. 기존엔 establish() 실패와 fd 전송 실패를 같은 메시지로 처리해 원인을 알 수 없었음 → ① establish() null 시 `VpnService.prepare()` 재확인으로 구분: Intent 반환 = 권한 없음 / null = **다른 VPN 앱 점유** (Android는 VPN 1개만 허용 — Surfshark 등 다른 VPN 앱이 켜져 있으면 실패) ② fd 전송 실패는 별도 메시지. 각 케이스를 로그 콘솔에도 기록
- [x] **사설 IP(LAN) VPN 경유 설정 추가** — VPN 화면 메인에 "사설 IP(LAN)도 VPN 경유" 스위치 (기본 OFF = 우회). OFF면 192.168.x.x / 10.x / 172.16~31.x / 169.254.x.x (IPv6 fc00::/7, fe80::/10 포함)는 VPN을 타지 않고 직접 접속 — 공유기·로컬 서버 접근 가능, 나머지 트래픽은 여전히 VPN 경유. Android 13(API 33)의 `VpnService.Builder.excludeRoute()` 사용, 하위 버전에서는 경고 로그 후 전체 경유

#### 완료 (2026-10-09, v2.11.9)
- [x] **VPN PERSIST_TUN_ACTION 응답 오류 수정 (치명)** — 로그로 최종 원인 확정: 이번엔 block-ipv6 push 옵션이 무시된 채 OPTIONS IMPORT(라우트/DNS/tun-mtu)까지 정상 진행, DSSERVER/DNSSERVER 쿼리도 정상 응답. 죽은 지점은 `PERSIST_TUN_ACTION` 쿼리 — 이것은 ics-openvpn 안드로이드 빌드 전용 쿼리로 `needok 'PERSIST_TUN_ACTION' ok` 가 아니라 **액션 이름(`NOACTION`/`OPEN_BEFORE_CLOSE`)으로 응답**해야 함. 'ok' 로 응답하면 openvpn 소스상 ASSERT(0) fatal 종료 (`Got unrecognised 'ok' from management for PERSIST_TUN_ACTION query`). 우리는 매번 새 tun을 여는 구조이므로 `OPEN_BEFORE_CLOSE` 로 응답하도록 전용 브랜치 추가 (ics-openvpn GUI 소스와 동일 판단)
- [x] NEED-STR 범용 응답 보정 — 알 수 없는 NEED-STR 쿼리에 `needok` 대신 `needstr` 명령으로 응답하도록 수정 (형식 불일치 시 쿼리 대기가 풀리지 않음)

#### 완료 (2026-10-09, v2.11.8)
- [x] **Block Images 켜면 페이지 전체 백지 버그 수정 (치명)** — 원인: `isImageRequest`의 Accept 판별이 `"image/" in accept`만 검사해서, 크롬 네비게이션 기본 Accept(`text/html,...,image/avif,image/webp,*/*`)에 `image/`가 섞여 있어 **메인 HTML 문서를 이미지로 오인해 차단** (구글 등 전부 백지). Accept 분기를 `image/` 포함 + `text/html`/`text/css` 미포함 조건으로 강화해 문서/스타일은 절대 차단되지 않도록 수정. Sec-Fetch-Dest 기반 판별은 기존 그대로
- [x] **VPN 서버 push 옵션 fatal 수정** — 로그로 원인 확정: 인증(AUTH)까지 정상 진행 후 서버가 push한 `block-ipv6`를 우리 openvpn 빌드가 인식하지 못해 `Options error: Unrecognized option ... [PUSH-OPTIONS]` → 프로세스 fatal 종료 → 이어서 management 상태가 깨져 `PERSIST_TUN_ACTION` 응답이 "unrecognized" 처리되며 최종 실패. argv에 `--ignore-unknown-option block-ipv6` 추가로 push 옵션을 무시하고 연결 진행. (NEED-OK 응답은 기존 범용 처리로 이미 응답 중)
- [x] **프린트 "Can print only from an activity" 수정** — 원인: AOSP PrintManager가 print() 시 `mContext instanceof Activity` 검사를 하는데, attachBaseContext에서 언어/테마 래퍼(`createConfigurationContext` 결과)가 base로 교체되면서 PrintManager의 mContext가 Activity가 아닌 ContextImpl이 됨. attach 시 래핑 **전 원본 base context**(outerContext=Activity)를 저장해 두고 printPage에서 PrintManager를 그 context로부터 획득하도록 변경

#### 완료 (2026-10-09, v2.11.7)
- [x] **VPN management 소켓 방향 불일치 수정 (치명)** — openvpn `--management <path> unix`는 기본이 서버 모드라 openvpn이 같은 경로에 자기 소켓을 열어 우리 accept()와 만나지 못함 (로그: openvpn은 "listening", 우리는 accept 대기 → 둘 다 기다림). `--management-client` 추가로 openvpn이 우리 소켓에 연결하도록 변경
- [x] **VPN 프로세스 종료 오감지 수정** — `--log` 도입으로 openvpn이 시작하자마 stdout(파이프)을 닫아 EOF가 났는데, EOF를 "프로세스 종료"로 처리해 살아있는 프로세스를 ERROR로 표시하던 것을 실제 종료(waitFor 블로킹) 시점으로 변경
- [x] **이미지 차단 실제 동작** — 기존 WebSettings.blockNetworkImage만으로는 CSS 배경/JS 삽입 이미지가 차단 안 됨 → shouldInterceptRequest에서 이미지 요청(Sec-Fetch-Dest: image 또는 Accept/확장자 판별)을 빈 응답으로 차단, 설정은 WebCleaner.blockImages로 통합
- [x] **프린트 정비** — 표준 어댑터(인자 없음) 우선 순서로 정리, 작업 이름에 페이지 제목 사용, 실패 시 예외 메시지를 토스트에 포함해 원확인 가능하도록

### 완료 (2026-10-09, v2.11.6)
- [x] **VPN 즉시 에러(exit 132, SIGILL) 수정 (치명)** — 원인: 싣고 있던 libjcopenvpn.so(ics-openvpn 0.7.68)는 공유 라이브러리(SONAME 있음, PT_INTERP 없음)라 직접 exec하면 시작 즉시 SIGILL로 죽음. ics-openvpn 0.7.68부터는 minivpn 런처(assets/pie_openvpn)가 libopenvpn.so를 LD_LIBRARY_PATH로 로드해 실행하는 구조 → jniLibs에 libjcminivpn.so(실행 파일) + libopenvpn.so(라이브러리, SONAME 그대로) 배치, 서비스는 minivpn을 LD_LIBRARY_PATH=nativeLibraryDir로 실행하도록 변경
- [x] VPN 로그 진단 보강 — EOF 직후 exitValue 레이스 제거(waitFor 3초 대기), jcovpn.log 파일이 없을 때도 "로그 파일 없음" 메시지를 콘솔에 남김 (이전엔 조용히 빠져서 덤프가 안 보였음)
- [x] **다운로드에 재생 세션 헤더 적용** — 동영상 감지 시점에 페이지가 본 요청의 User-Agent/Referer/Origin/Cookie/Accept를 캡처해 DetectedVideo/DlItem에 저장, 다운로드(고속 분할 + ffmpeg 경로 모두)가 이 헤더를 그대로 사용 — 세션이 UA/쿠키에 묶인 사이트와 HLS AES-128 복호화 키 요청도 재생과 동일한 세션으로 받을 수 있음. UA 보충은 하드코딩 Chrome/120 대신 실제 웹뷰 기본 UA 사용. 이어받기(재개) 시에도 저장된 헤더 재사용

### 완료 (2026-10-09, v2.11.5)
- [x] **네이버 등 일부 사이트 무한 로딩/추천 콘텐츠 실패 수정 (치명)** — 원인 2가지: ① 스캐너 JS 주입을 위해 가로채던 문서 판별이 `Accept: */*` + 확장자 없는 모든 GET을 잡아냈는데, 네이버 추천 피드 같은 fetch 기반 API가 정확히 이 조건에 걸려 중간에서 한 번 더 받은 뒤 폐기(API 실패/중복 실행). 스트리밍·롱폴 엔드포인트면 읽기가 멈춰 로딩이 끝나지 않았음 → Sec-Fetch-Dest(크롬 80+)로 document/iframe만 가로채도록 변경, 구형 웹뷰는 Accept에 text/html 명시된 경우만. ② 가로챈 HTML 문서 응답의 Set-Cookie가 여러 개면 `", "`로 합쳐져(Expires 날짜에 쉼표) 쿠키 파싱이 깨짐 — 네이버 메인 쿠키(NNB 등)가 전부 엉망이 돼 같은 URL의 API가 계속 실패, 새로고침해도 반복됐음 → Set-Cookie는 응답 헤더에서 빼고 원본 값 각각을 CookieManager에 직접 저장

## 완료 (2026-10-09, v2.11.4)
- [x] **OpenVPN 즉시 종료 진단 강화** — openvpn 프로세스가 출력 0줄로 바로 죽는 케이스를 추적하기 위해: `--log <cache>/jcovpn.log` 인자 추가(openvpn 자체 로그 파일), 로그 스레드 EOF 시 `exitValue()` 종료 코드 기록 + 로그 파일 덤프(비어있으면 "크래시 추정", 있으면 마지막 15줄) 후 ERROR 상태 전환. 다음 테스트에서 로그 콘솔의 `exit=` 라인과 덤프로 원인 확정 가능
- [x] **앱 전체 하드코딩 문자열 리소스화 (언어 혼용 수정)** — 즐겨찾기/다운로드/히스토리/토렌트/VPN 화면과 전체 레이아웃에 하드코딩돼 있던 한글·영어 문자열을 전부 strings.xml(ko/en)로 이동. 시스템/한국어/영어 설정에 따라 UI 언어가 일관되게 표시됨. 접근성 설명(contentDescription) 17개 신규 키 추가
- [x] **JcUi 통합 스타일 키트** — `ui/JcUi.kt` 신규: 파스텔 팔레트(blue #7C9BD4 / green #6FA88C / red #D08484 / slate #8E99A8 / amber #D4A574) + 높이 40dp 고정 알약 버튼 + 40dp 원형 아이콘 버튼 + 모서리 16dp 카드. VpnActivity의 지역 스타일 헬퍼를 JcUi 위임으로 교체해 화면별 색감/버튼 크기 불일치 해소 시작

### 완료 (2026-10-09, v2.11.3)
- [x] **VPN 연결 "Connecting…" 무한 대기 수정 (치명)** — 원인: `LocalServerSocket(FileDescriptor)` 생성자는 bind만 하고 **listen()을 호출하지 않아** openvpn 이 management unix 소켓에 connect 하지 못하고 종료됐으나, 우리는 accept()에서 계속 대기해 상태가 CONNECTING 에 멈춤. `Os.listen(fd, 4)` 추가로 해결. 프로세스가 죽었는데 UI에 안 보이던 문제도 함께 수정 — stdout 스트림 종료 감지 시 마지막 fatal 라인("Cannot open…", "Options error:", "AUTH_FAILED" 등)으로 ERROR 상태 전환
- [x] VPN 로그 콘솔 — 메인 화면 [로그] 버튼 on/off 로 터미널 스타일 콘솔(최대 300줄 링 버퍼) 표시. openvpn 출력/상태 전환/management 이벤트(HOLD/인증/OPENTUN 등)를 타임스탬프와 함께 기록. 화면 전체를 ScrollView 로 감싸 콘솔이 길어도 스크롤 가능
- [x] 눈알(비밀번호 표시) 버그 수정 — inputType 비트 검사가 PASSWORD(0x80)/VISIBLE_PASSWORD(0x90) 비트 겹침으로 항상 참이 돼 토글이 안 먹던 것을 별도 플래그 추적으로 수정

### 완료 (2026-10-09, v2.11.2)
- [x] 메뉴 VPN 중복 표시 제거 — "VPN" 그룹 헤더+하위 "VPN" 항목이 중복되던 것을 헤더 하나로 통합. MenuGroup에 direct(헤더 탭으로 바로 실행, 아코디언 없음)/label(동적 라벨) 옵션 추가
- [x] 메뉴 VPN 항목에 현재 연결 상태 표시 — "VPN — kr-seo-udp"(연결됨) / "VPN — 연결 중…" / "VPN — 연결 실패"
- [x] VPN 메인 화면에 뒤로 버튼 추가 (왼쪽 상단 ←, 화면 닫기) + 시스템 백키 처리: 프로파일 관리 화면이면 메인으로, 메인이면 종료

### 완료 (2026-10-09, v2.11.1)
- [x] **OpenVPN 실행 실패 수정 (치명)** — "Cannot run program … error=13, Permission denied": 안드로이드 10+(targetSdk 29~)부터 앱 홈 디렉터리(filesDir) 파일 exec 금지. assets 추출 방식을 폐기하고 바이너리를 jniLibs(`libjcopenvpn.so`)로 이동해 `nativeLibraryDir`에서 실행 (ics-openvpn과 동일). 매니페스트 `extractNativeLibs="true"` + `packaging.jniLibs.useLegacyPackaging` 추가로 설치 시 실행 비트와 함께 추출. 부수 효과: .so 압축 저장으로 universal APK 158→81MB
- [x] 프로파일 이름 개선 — SAF 선택 시 `lastPathSegment`가 MediaStore ID(msf:173060)를 반환하던 문제 수정: contentResolver의 DISPLAY_NAME으로 실제 파일명 조회. 이미 msf:xxx로 저장된 프로파일은 로드 시 remote 호스트 라벨+프로토콜 이름(예: kr-seo-udp)으로 자동 복구
- [x] 국가 표기 — ovpn의 remote 호스트 첫 라벨에서 국가 코드 추출(예: kr-seo.prod.x.com → KR), 목록 부제목에 깃발 이모지+코드+파일명 표시
- [x] 프로파일 목록 자연 정렬 (msf:2 < msf:10, 숫자 수치 비교)
- [x] 연결 상태 표시 — 목록 항목에서 연결 중(파랑)/연결됨(초록)/연결 실패(빨강, 에러 메시지는 메인 카드에) 단계 표시, 실패 시 버튼은 다시 "연결"(재시도)
- [x] 크리덴셜 관리 — 연필 버튼을 이름 변경+아이디/비밀번호 수정 다이얼로그로 확장, 비밀번호 필드에 눈알(표시/숨김) 토글 추가 (연결 시 인증 입력창에도 동일 적용)

### 완료 (2026-10-09, v2.11.0)
- [x] VPN 화면 전면 재디자인 (일반 VPN 앱 스타일) — 메뉴에서 VPN을 별도 그룹으로 분리. 첫 화면: 상태 카드(연결된 프로파일 이름 + 실시간 속도 ▼다운/▲업 + 연결 시간 + 연결 해제 버튼, 미연결 시 자물쇠 아이콘+안내) 아래에 "프로파일 관리" 버튼. 관리 화면: 추가(직접 입력/파일 1개 선택), 여러 개 가져오기, 목록 항목마다 연결(초록 알약)/연결 해제(빨강 알약)+이름 변경+삭제, 연결 진행 상태가 항목 아래에 표시. 버튼을 둥근 알약/원형 스타일로 개선
- [x] VPN 속도 표시 — management bytecount 5 응답을 파싱해 rx/tx 속도(bytes/s)와 누적량, 연결 시각을 서비스 싱글턴에 게시, UI는 1초 폧링으로 갱신
- [x] .ovpn 직접 입력 추가 — 프로파일 추가 시 "직접 입력"(설정 텍스트 붙여넣기+이름) / "파일에서 가져오기" 중 선택 (VpnProfiles.importText 추가)
- [x] 메뉴 재배치 — "이미지 다운로드 최소 크기"를 일반 그룹에서 다운로드 설정 그룹으로 이동

### 완료 (2026-10-09, v2.10.0)
- [x] OpenVPN VPN 지원 (브라우저 전용) — ics-openvpn의 openvpn 2.x 안드로이드 바이너리 임베드(assets/vpn/openvpn.<abi>, GPL v2 — 출처/라이선스는 assets/vpn/NOTICE.md). VpnService.Builder의 addAllowedApplication(자기 패키지)로 **브라우저 트래픽만 VPN 경유**, 나머지 앱은 영향 없음. management unix 소켓 연동: OPENTUN tun fd 전달, PROTECTFD 소켓 보호, Auth 사용자/비밀번호 쿼리 응답, IFCONFIG/DNS/ROUTE 수집해 인터페이스 구성
- [x] VPN 프로파일 관리 (메뉴 → 일반 → VPN) — .ovpn 추가(파일 선택+이름 입력), 여러 개 한번에 import(파일 이름을 이름으로), 이름 변경, 삭제, 연결/해제. auth-user-pass 있는 프로파일은 연결 시 아이디/비밀번호 입력(저장), 없는 프로파일은 그대로 사용. 첫 연결 시 시스템 VPN 권한(VpnService.prepare) 승인
- [x] 이미지 다운로드 최소 크기 설정 (메뉴 → 일반 → 이미지 다운로드 최소 크기, 100~2000px, 기본 300) — 페이지 스캐너 JS에 window.__sbMinImg 로 주입

### 완료 (2026-10-08, v2.9.0)
- [x] 일반 파일 다운로드(zip/pdf 등, 파일 탭) 진행 표시 개선 — 진행 바(퍼센트) + 다운로드 속도(KB/s, MB/s) 표시. 시스템 다운로드 매니저의 500ms 갱신 주기로 수신량 차이를 계산해 속도 산출

### 완료 (2026-10-08, v2.8.9)
- [x] 전체화면 진입 시 사이트 에러("Sorry, an error has occurred") 수정 — 팝업 차단이 서브도메인 순환 스트리밍 사이트(m02.x.com → m05.x.com)의 전체화면 JS 이동을 오인 차단하던 것을 완화: 차단 기준을 정확한 호스트 대신 베이스 도메인(등록 도메인, co.kr 등 3단계 대응) 비교로 변경 + 클릭 후 5초 이내 비동기 이동은 사용자 유도로 허용
- [x] v2.8.8의 캐시 헤더 전달에서 X-Frame-Options/CSP를 제외 — 우리가 재전송하는 iframe 문서의 프레임 임베딩이 막혀 플레이어가 깨지는 문제 방지

### 완료 (2026-10-08, v2.8.8)
- [x] 페이지 로딩 속도 개선 — 스캐너 JS 주입용 재다운로드 fetch가 gzip을 요청하지 않아 HTML을 압축 없이 받던 문제 수정 (Accept-Encoding: gzip + GZIPInputStream), 원본 응답의 캐시 헤더를 WebResourceResponse에 전달해 재방문/뒤로가기 캐시 히트 개선

### 완료 (2026-10-08, v2.8.7)
- [x] 주소창 크롬 스타일 편집: 첫 탭에서 전체 선택 (setSelectAllOnFocus), 이후 탭은 그 위치에 커서

### 완료 (2026-10-07, v2.8.6)
- [x] 팝업 차단 ON 상태에서 Google 검색이 먹통이던 문제 — 팝업 차단이 "모든 새창 차단" 모드에서 제스처 없는 메인프레임 이동을 무조건 차단해서, Google의 JS 리다이렉트/검색 흐름까지 막았음. 이제 메인프레임 이동은 사이트를 벗어나는 이동만 차단 (새 창 window.open 차단은 기존과 동일)

### 완료 (2026-10-07, v2.8.5)
- [x] 전체화면 동영상 길게 눌러도 톱니(⚙)가 안 뜨던 문제 — 사이트마다 전체화면 구현이 달라 3가지 케이스 모두 지원: ①네이티브 HTML5 풀스크린(onShowCustomView)에 롱클릭 리스너 추가, ②div 래퍼를 fullscreen으로 쓰는 경우 document.fullscreenElement에서 비디오 탐색, ③기존 JS 풀스크린
- [x] 영상 메뉴의 "전체화면 OFF"가 네이티브 풀스크린도 종료하도록 처리 (chromeClient.onHideCustomView)

### 완료 (2026-10-07, v2.8.4)
- [x] 토렌트 업로드 방지: 메뉴 → 토렌트 그룹에 "다운 완료 후 업로드 중지" 토글 추가 — 완료되는 순간 시딩을 멈춰 업로드가 아예 안 생김 (상태는 완료 유지). 속도 제한 0은 무제한이므로 최소 1 KB/s 권장
- [x] 다운로드 목록 썸네일: 완료된 영상은 로컬 파일에서 프레임 캡처, 이미지는 다운샘플 미리보기 표시 (캡처 실패 시 기존 종류 타일)

### 완료 (2026-10-07, v2.8.3)
- [x] HLS 재생 실패 진짜 원인 수정: 플레이어 HTML의 <script>에 `&amp;&amp;` 엔티티를 써서 JS 전체가 SyntaxError 난 것 → plain `&&`로 교정 (script 낶은 엔티티 디코딩 안 됨). hls.js 경로 + 네이티브 폴드백 둘 다 살아남
- [x] HLS 고속 다운로드 병합본이 .ts(MPEG-TS)라 재생 0:00이던 문제 → ffmpeg 스트림 복사로 .mp4 재먹스 (실패 시 ts 유지)
- [x] HLS 감지 누락 보강: 스캐너 JS가 상대 경로 m3u8을 절대 URL로 변환해 수집, XHR/fetch 요청 URL 자체가 미디어 링크면 직접 보고, accept가 */*인 확장자 없는 iframe 문서에도 스캐너 주입 시도 (새로고침 시 캐시 때문에 네트워크 감지가 안 뜨는 경우 대비)
- [x] 토렌트 속도 제한 다이얼로그: 다운로드/업로드 라벨 + (KB/s) 단위 표기 + "0 = 무제한" 안내 노트 추가

### 완료 (2026-10-07, v2.8.2)
- [x] 토렌트 속도 제한 메뉴에 단위 표기 — "토렌트 속도 제한 (다운 500 KB/s · 업로드 무제한)" 형식, 0=무제한 명시
- [x] 토렌트/magnet 열기 다이얼로그에 "파일에서 .torrent 선택" 추가 — 파일 관리자에서 .torrent 골라 바로 다운로드 시작
- [x] 다운로드 화면 분리: "비디오" 탭 = 스트리밍 영상(HLS/MP4 등), "파일" 탭 = 일반 파일(시스템 다운로드) + 이미지 다운로드
- [x] 다운로드 목록 재생을 외부 앱 대신 내장 플레이어로 (외부 플레이어가 HLS 병합본 재생 못 해 0:00 멈추던 문제)
- [x] 영상 목록 썸네일: poster 없는 직접 MP4/WEBM 링크는 MediaMetadataRetriever로 프레임 캡처해 표시 (실패 시 기존 색상 타일)

### 완료 (2026-10-07, v2.8.1)
- [x] 앱 아이콘 교체: 어두운 배경 + 초록 네온 "JC / BROWSER" 디자인 (adaptive foreground/background 포함)
- [x] 다운로드/토렌트 관리 화면 탭 선택 UI: 흰색 칩(겹침) → 파랑 밑줄 인디케이터로 변경, 탭 간격 조정
- [x] 메뉴 구조: 토렌트 항목(지원 토글/다운로드 관리/동시 개수/속도 제한/magnet 열기)을 "다운로드 설정"에서 별도 "토렌트" 그룹으로 분리

### 완료 (2026-10-06, v2.8.0)
- [x] 토렌트 전면 개편: "받으면서 재생" 제거 → 전체 파일 다운로드 방식. .torrent/magnet은 토렌트 지원 ON일 때만 공유 파일 자동 다운로드 (OFF면 .torrent만 일반 다운로드, magnet은 켜기 안내)
- [x] 토렌트 다운로드 관리 화면 신규 (메뉴 → 다운로드 설정 → 토렌트 다운로드): 진행 중/완료/취소·실패 필터, 진행률+다운·업로드 속도+시드/피어 표시, 일시 중지/재시작/취소, 완료 항목 실행(영상은 내장 플레이어), 삭제 (취소·실패 항목은 받던 파일도 함께 삭제)
- [x] 토렌트 설정 추가: 동시 다운로드 개수 (1~5), 다운/업로드 속도 제한 (KB/s, 0=무제한, libtorrent SettingsPack), 완료 파일은 기존 "다운로드 위치" 설정 폴더로 납품
- [x] HLS 재생 실패 대응: hls.js FATAL 또는 8초 무응답 시 네이티브 재생으로 자동 폴드백 + 에러 오버레이에 사유 표시 유지
- [x] 톱니(⚙) 플로팅 버튼: video 태그 존재 시 항상 표시 → 전체화면에서 동영상 길게 누르기 시에만 2초간 표시 (Soul 스타일, 설정 안 하면 자동 숨김). 비전체화면 길게누르기는 기존처럼 바로 메뉴
- [x] 프린트 실패 시 무인자 PrintDocumentAdapter로 재시도 + 실패 토스트
- [x] 일반 파일 다운로드 화면: 다운로드 관리에 "파일" 탭 추가 (시스템 다운로드 매니저의 zip/pdf 등 조회, 열기/삭제)
- [x] 앱 아이콘: 파랑 그라데이션 둥근 사각 + 흰색 "JC" (adaptive foreground/background 포함)

### 완료 (2026-10-06, v2.4.0)
- [x] 일부 사이트에서 광고차단/팝업차단 미동작 → 기본 필터에 AdGuard Base + EasyList 추가, 기존 사용자에게도 누락분 자동 반영
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
- 빌드: `gradle.bat --no-daemon assembleDebug`, 배포: C:\Temp\workspace\ + Z:\Share 양쪽 복사 (Downloads 아님)
