# Playback Test

기기 안의 음악 파일을 골라 재생하면서 **여러 오디오 출력 경로**를 비교해 볼 수 있는 안드로이드 앱입니다.
Android Studio 없이 **명령줄(CLI)과 Gradle만으로** 빌드할 수 있도록 구성되어 있습니다.

## 기능

| 기능 | 설명 |
| :--- | :--- |
| 파일 열기 | 안드로이드 기본 파일 선택기로 음악 파일 선택 (저장소 권한 불필요) |
| 재생 / 일시정지 | 버튼 하나로 전환 |
| 재생 모드 선택 | 재생 중에도 라디오 버튼으로 바꾸면 현재 위치에서 이어서 재생 |
| 재생 시간 | `진행시간 / 전체시간` 표시 |
| 볼륨 | 현재 볼륨 표시, `볼륨 +/-` 버튼, 물리 볼륨 키 연동 |

### 재생 모드

| 모드 | 구현 | 비고 |
| :--- | :--- | :--- |
| Deep buffer (기본) | Media3 ExoPlayer, Offload 끔 | 일반 재생 경로 |
| Offload | Media3 ExoPlayer, Offload 켬 | 기기가 지원하는 형식(주로 MP3, AAC)일 때 적용, 아니면 일반 경로로 재생 |
| Fast track | MediaCodec 디코딩 + `AudioTrack` (`PERFORMANCE_MODE_LOW_LATENCY`) | 모노/스테레오만 지원 |
| AAudio (MMAP) | MediaCodec 디코딩 + 네이티브 AAudio (EXCLUSIVE + LOW_LATENCY) | C++ (NDK) 코드 사용, 모노/스테레오만 지원 |

AAudio 모드를 선택하면 화면 아래에 실제로 적용된 공유 모드와 버퍼 크기가 표시됩니다.

## 빌드 요구사항

| 항목 | 버전 |
| :--- | :--- |
| OS | Windows 10/11 (PowerShell 기준으로 설명, Linux/macOS도 같은 방식으로 가능) |
| JDK | **17** |
| Android SDK Platform | `android-34` |
| Android SDK Build-Tools | `34.0.0` |
| Android NDK | `25.1.8937393` |
| CMake | `3.22.1` |
| Gradle | 8.5 (Gradle Wrapper가 자동으로 내려받음, 별도 설치 불필요) |
| Android Gradle Plugin | 8.2.2 |
| minSdk / targetSdk | 26 / 34 |

디스크 여유는 약 5GB, 첫 빌드 때는 인터넷 연결이 필요합니다. (NDK만 약 1GB)

## 빌드 환경 구축

### 1. JDK 17 설치

[Eclipse Temurin 17](https://adoptium.net/) 등 JDK 17을 설치하고 확인합니다.

```powershell
java -version
```

`17`로 시작하는 버전이 나와야 합니다. 다른 버전이 나오면 `JAVA_HOME` 환경 변수를 JDK 17 설치 폴더로 지정하세요.

### 2. Android Command Line Tools 설치

1. [Android 개발자 사이트](https://developer.android.com/studio#command-line-tools-only)의 **Command line tools only**에서 Windows용 zip을 받습니다.
2. SDK 폴더를 만들고, 압축을 풀어 **반드시 아래 구조**로 배치합니다. (`cmdline-tools\latest`가 아니면 `sdkmanager`가 SDK 위치를 인식하지 못합니다.)

```
C:\android-sdk\
 └── cmdline-tools\
      └── latest\
           ├── bin\
           ├── lib\
           └── ...
```

3. 환경 변수를 등록합니다. (새 PowerShell 창을 열어야 적용됩니다.)

```powershell
[Environment]::SetEnvironmentVariable("ANDROID_HOME", "C:\android-sdk", "User")
```

### 3. SDK 구성 요소 설치

```powershell
C:\android-sdk\cmdline-tools\latest\bin\sdkmanager --licenses

C:\android-sdk\cmdline-tools\latest\bin\sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;25.1.8937393" "cmake;3.22.1"
```

`--licenses`는 계속 `y`를 입력해 모두 동의합니다.

### 4. 소스 받기

```powershell
git clone https://github.com/<사용자명>/<저장소명>.git
cd <저장소명>
```

### 5. SDK 위치 알려주기

`ANDROID_HOME`을 등록했다면 이 단계는 생략해도 됩니다. 확실하게 하려면 프로젝트 최상위에 `local.properties`를 만드세요. 이 파일은 PC마다 경로가 달라서 Git에는 올라가지 않습니다.

```
sdk.dir=C\:\\android-sdk
```

메모장으로 만들 때는 **UTF-8(BOM 없음)** 으로 저장하세요.

### 6. 빌드

```powershell
.\gradlew assembleDebug
```

- 첫 빌드는 Gradle, 라이브러리, NDK 컴파일 때문에 시간이 오래 걸립니다. 이후 빌드는 훨씬 빠릅니다.
- 결과물: `app\build\outputs\apk\debug\app-debug.apk`

PowerShell에서는 현재 폴더의 스크립트를 실행할 때 `.\`를 반드시 붙여야 합니다. (`gradlew`만 입력하면 인식하지 못합니다.)

## 폰에 설치하기

### 방법 A. APK 파일 직접 설치

`app-debug.apk`를 폰으로 옮겨 열면 설치됩니다. "출처를 알 수 없는 앱 설치" 허용이 필요할 수 있습니다.

### 방법 B. USB로 바로 설치 (개발할 때 편함)

1. 폰의 **설정 → 개발자 옵션 → USB 디버깅**을 켭니다. (개발자 옵션은 설정 → 휴대전화 정보 → 소프트웨어 정보에서 빌드번호를 7번 누르면 나타납니다.)
2. USB로 연결하고 폰에 뜨는 **USB 디버깅 허용** 팝업에서 허용합니다.
3. 확인 후 설치합니다.

```powershell
C:\android-sdk\platform-tools\adb devices
.\gradlew installDebug
```

`adb devices` 결과가 `device`여야 합니다. `unauthorized`라면 아래 문제 해결을 참고하세요.

로그는 `adb logcat *:E`로 볼 수 있고, AAudio 관련 로그만 보려면 `adb logcat -s AAudio AAudioPlayer`를 쓰세요.

## 문제 해결

| 증상 | 원인 / 해결 |
| :--- | :--- |
| `SDK location not found` | `local.properties`를 만들거나 `ANDROID_HOME`을 등록 (새 PowerShell 창에서 확인) |
| `Unsupported class file major version` 등 JDK 오류 | JDK 17이 아닌 버전 사용 중. `java -version`과 `JAVA_HOME` 확인 |
| `NDK ... not found`, `CMake ... not found` | 3단계의 `ndk;25.1.8937393`, `cmake;3.22.1` 설치 확인 |
| `Failed to install the following Android SDK packages ... license` | `sdkmanager --licenses` 실행 후 모두 동의 |
| `processDebugMainManifest FAILED` | `AndroidManifest.xml`의 `xmlns:android="http://schemas.android.com/apk/res/android"` 주소 확인 (BOM 없는 UTF-8로 저장) |
| `adb devices`가 `unauthorized` | 폰 화면을 켜고 USB 디버깅 허용 팝업 확인. 개발자 옵션의 **USB 디버깅 승인 취소** 후 케이블 재연결. 삼성 폰은 **설정 → 보안 및 개인정보 보호 → 자동 차단**을 끄세요 |
| `No online devices found` | 위와 같은 원인이거나 USB 연결 모드가 충전 전용. 파일 전송(MTP)으로 변경, 다른 케이블 시도 |
| 첫 빌드가 매우 느림 / 메모리 부족 | `gradle.properties`의 `org.gradle.jvmargs=-Xmx1024m`처럼 메모리 조정, 다른 프로그램 종료 |
| 앱 실행 시 "16KB와 호환되지 않음" 안내 | 이 프로젝트는 `CMakeLists.txt`에서 16KB 정렬로 이미 대응함. 그래도 뜨면 `app\build.gradle`의 `android { }`에 `packaging { jniLibs { useLegacyPackaging true } }` 추가 |

## 알아둘 점

- **지원 ABI**: 빌드 시간을 줄이려고 `arm64-v8a`(64비트 ARM)만 빌드합니다. 대부분의 최신 폰에 해당합니다. 32비트 기기나 x86 에뮬레이터에서 실행하려면 `app\build.gradle`의 `abiFilters`에 `'armeabi-v7a'`, `'x86_64'`를 추가하세요.
- **minSdk 26**: AAudio가 Android 8.0부터 지원되기 때문입니다.
- **실기기 권장**: 오디오 출력 경로(Offload, Fast track, MMAP)는 기기와 드라이버에 따라 동작이 달라서 에뮬레이터에서는 의미 있는 비교가 어렵습니다.
- **Offload / MMAP 적용 여부 확인**: 재생 중에 `adb shell dumpsys media.audio_flinger`로 출력 스레드를 확인할 수 있습니다.
- **디버그 빌드**: 개인 테스트용입니다. 배포하려면 서명 키로 서명한 릴리스 빌드가 필요하며, 서명 키(`*.jks`, `*.keystore`)는 저장소에 올리지 마세요.

## 프로젝트 구조

```
.
├── build.gradle               # 최상위 빌드 설정 (AGP 버전)
├── settings.gradle
├── gradle.properties
├── gradlew, gradlew.bat       # Gradle Wrapper
├── gradle/wrapper/
└── app/
    ├── build.gradle           # 앱 모듈 설정 (SDK 버전, 의존성, NDK/CMake)
    └── src/main/
        ├── AndroidManifest.xml
        ├── cpp/
        │   ├── CMakeLists.txt
        │   └── aaudio_player.cpp      # AAudio 네이티브 코드 (JNI)
        ├── java/com/example/myapp/
        │   ├── MainActivity.java      # 화면, 모드 전환, 볼륨, 시간 표시
        │   ├── LowLatencyPlayer.java  # Fast track / AAudio 공통 인터페이스
        │   ├── FastTrackPlayer.java   # AudioTrack 저지연 재생
        │   └── AAudioPlayer.java      # AAudio 재생 (JNI 호출)
        └── res/
            ├── layout/activity_main.xml
            ├── drawable/              # 아이콘 배경/전경 (벡터)
            └── mipmap-anydpi-v26/     # 적응형 아이콘
```
