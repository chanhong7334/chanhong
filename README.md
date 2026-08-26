# 내 폰 웹캠 (Phone Webcam for VOOV)

스마트폰(Android)을 PC의 웹캠처럼 쓸 수 있게 해주는 프로그램입니다. Iriun Webcam과 같은 방식으로 동작합니다.

- **폰 앱** (`app/`): 카메라 화면을 찍어서 같은 Wi-Fi에 있는 PC로 실시간 전송하는 Android 앱
- **PC 서버** (`pc_server/`): 폰에서 온 영상을 받아서 Windows의 **가상 카메라 장치**로 만들어주는 프로그램. VOOV, Zoom, OBS 등 카메라를 선택할 수 있는 모든 프로그램에서 이 가상 카메라를 선택하면 폰 카메라 화면이 나옵니다.

```
[폰 카메라] --Wi-Fi(WebSocket)--> [PC 서버, server.py] --가상카메라--> [VOOV 등 화상회의 앱]
```

> ⚠️ **중요한 제약사항**: 이 작업 환경(클라우드 샌드박스)에는 Android SDK와 `dl.google.com` 접속이 막혀 있어서, 여기서 바로 설치용 `.apk` 파일을 빌드해 드릴 수는 없었습니다. 대신 **완전한 Android Studio 프로젝트 소스코드**를 만들어 두었으니, 아래 안내대로 본인 PC에서 딱 한 번 "빌드" 버튼만 누르면 실제 앱 파일(.apk)이 나옵니다. (Android Studio가 알아서 필요한 것들을 다운로드하고 빌드해줍니다. 보통 5~15분 정도 걸립니다.)

---

## 1단계. PC 준비 (Windows, VOOV를 실행할 그 PC)

1. **OBS Studio** 설치: https://obsproject.com/ (무료) — 설치만 하면 됩니다.
2. OBS를 한 번 실행 → 오른쪽 하단 **"가상 카메라 시작"** 클릭 → 바로 **"중지"** 클릭 → OBS 종료.
   - 이 과정은 Windows에 "OBS Virtual Camera"라는 가상 카메라 장치를 등록하는 1회성 작업입니다. 이후로는 OBS를 켜둘 필요가 없습니다.
3. **Python 3.9 이상** 설치: https://www.python.org/downloads/ (설치 시 "Add python.exe to PATH" 체크)
4. 이 폴더(`PhoneWebcam`)를 PC로 복사한 뒤, 명령 프롬프트(cmd)에서:
   ```
   cd PhoneWebcam\pc_server
   pip install -r requirements.txt
   python server.py
   ```
5. 화면에 아래처럼 IP 주소가 뜹니다. 이걸 기억해두세요 (폰 앱에서 입력합니다).
   ```
   PC IP 주소: 192.168.0.10   (포트: 8765)
   ```
   이 창은 폰을 웹캠으로 쓰는 동안 계속 켜져 있어야 합니다.

## 2단계. 폰 앱 빌드하기 (딱 한 번만 하면 됩니다)

1. PC에 **Android Studio** 설치: https://developer.android.com/studio (무료)
2. Android Studio 실행 → **Open** → 이 `PhoneWebcam` 폴더 선택
3. 처음 열면 자동으로 필요한 구성요소를 다운로드합니다 (Gradle, Android SDK 등, 인터넷 필요, 몇 분 소요) — 그냥 기다리시면 됩니다.
4. 상단 메뉴 **Build → Build App Bundle(s) / APK(s) → Build APK(s)** 클릭
5. 빌드가 끝나면 오른쪽 아래에 뜨는 알림에서 **"locate"**를 눌러 만들어진 `app-debug.apk` 파일 위치를 확인
   - 보통 경로: `PhoneWebcam\app\build\outputs\apk\debug\app-debug.apk`
6. 이 `app-debug.apk` 파일을 폰으로 옮깁니다 (카카오톡 '나에게 보내기', 이메일, USB 케이블 등 편한 방법 아무거나).
7. 폰에서 그 파일을 눌러 설치합니다.
   - "출처를 알 수 없는 앱" 경고가 뜨면 **설정 → 허용**해주세요 (내가 직접 만든 앱이라 발생하는 정상적인 경고입니다).

> 대안: Android Studio 대신 명령줄만 쓰고 싶다면, 프로젝트 폴더에서 `gradlew.bat assembleDebug` 를 실행해도 동일한 `app-debug.apk`가 만들어집니다.

## 3단계. 사용하기

1. **폰과 PC를 같은 Wi-Fi**에 연결합니다. (핫스팟도 되지만, 폰이 핫스팟을 켜면 PC가 그 핫스팟에 접속해야 합니다)
2. PC에서 `python server.py` 실행 (1단계 4번)
3. 폰에서 "내 폰 웹캠" 앱 실행 → 카메라 권한 허용
   - 몇 초 안에 PC IP가 자동으로 입력창에 채워질 수 있습니다 (자동 검색). 안 채워지면 1단계에서 확인한 IP를 직접 입력하세요.
4. **연결** 버튼 클릭 → 상단에 "✅ 연결됨"이 뜨면 성공
5. **VOOV Meeting** 실행 → 설정(⚙) → 오디오/비디오 → 카메라 목록에서 **"OBS Virtual Camera"** 선택
6. 회의에 들어가면 폰 카메라 화면이 나옵니다.

- 카메라 전환(전/후면)은 앱의 "카메라 전환" 버튼으로 가능합니다.
- 화질/프레임이 끊기면 Wi-Fi 신호를 확인하거나, `pc_server/server.py`의 `WIDTH, HEIGHT` 값을 낮춰보세요 (예: 960, 540).

## 문제 해결

| 증상 | 확인할 것 |
|---|---|
| 폰에서 연결 실패 | 폰·PC가 같은 Wi-Fi인지, Windows 방화벽이 8765/47777 포트를 막고 있지 않은지 확인 (처음 `server.py` 실행 시 방화벽 허용 팝업이 뜨면 "허용") |
| VOOV 카메라 목록에 "OBS Virtual Camera"가 없음 | OBS Studio를 설치했는지, "가상 카메라 시작"을 한 번이라도 눌렀었는지 확인 |
| 화면이 까맣게 나옴 | `server.py` 실행 창에서 "폰 연결됨" 로그가 뜨는지 확인 (안 뜨면 폰 앱에서 연결이 안 된 것) |
| 자동 검색으로 IP가 안 채워짐 | 일부 공유기는 브로드캐스트를 막습니다. IP 입력창에 1단계에서 본 IP를 수동으로 입력하세요 |

## 폴더 구조

```
PhoneWebcam/
├── app/                     # Android 앱 소스 (Kotlin, CameraX + OkHttp WebSocket)
│   └── src/main/java/com/phonewebcam/app/MainActivity.kt
├── pc_server/
│   ├── server.py            # PC에서 실행하는 서버 (aiohttp + pyvirtualcam)
│   └── requirements.txt
├── build.gradle, settings.gradle, gradlew(.bat)  # Android Studio 프로젝트 파일
└── README.md
```
