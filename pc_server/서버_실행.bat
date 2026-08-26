@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo ============================================
echo   Phone Webcam PC Server / 폰 웹캠 서버
echo ============================================
echo.

where python >nul 2>nul
if errorlevel 1 (
    echo [오류] Python이 설치되어 있지 않습니다.
    echo https://www.python.org/downloads/ 에서 설치한 뒤 이 파일을 다시 실행하세요.
    echo ^(설치 화면에서 반드시 "Add python.exe to PATH"를 체크하세요^)
    echo.
    pause
    exit /b 1
)

echo [1/2] 필요한 패키지를 확인/설치합니다. 처음 실행 시 몇 분 걸릴 수 있습니다...
python -m pip install --quiet --disable-pip-version-check -r requirements.txt
if errorlevel 1 (
    echo.
    echo [오류] 패키지 설치에 실패했습니다. 인터넷 연결을 확인한 뒤 다시 실행해주세요.
    pause
    exit /b 1
)

echo [2/2] 서버를 시작합니다.
echo.
echo  - 화면에 나오는 IP 주소를 폰 앱에 입력하세요.
echo  - Windows 방화벽 허용 팝업이 뜨면 반드시 "액세스 허용"을 눌러주세요.
echo  - 이 창을 닫으면 폰 웹캠 연결이 끊깁니다. VOOV 사용 중에는 계속 켜두세요.
echo  - 종료하려면 이 창에서 Ctrl+C 를 누르세요.
echo.

python server.py

echo.
echo 서버가 종료되었습니다.
pause
