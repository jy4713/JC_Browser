# JC Browser 프로젝트

스트리밍/미디어 다운로드 중심 안드로이드 브라우저 (WebView 기반, Soul/TinCat 스타일 UI)

- 저장소: https://github.com/jy4713/JC_Browser
- 작업 디렉터리: `C:\Temp\workspace\JC_Browser\stream-browser`
- 현재 버전: 2.11.3 (versionCode 57)

## 이슈 트래커

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
- [x] 토렌트 설정 추가: 동시 다운로드 개수 (1~5), 다운/업로드 속도 제한 (KB/s, 0=무제한, libtorrent SettingsPack), 완료 파일은 기존 "다운로드 위치" 설정 폴터로 납품
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
