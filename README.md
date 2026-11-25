# 조업기록(fishing-log)

국립수산과학원 연근해자원과의 공공 AX 컨설팅(PoC) 과제 코드입니다.

- 과제명: 어업인 현장지식의 데이터화를 통한 수산자원 표본조사체계 AX 시범 구축
- 기간: 2026. 10. ~ 11.
- 담당: 연근해자원과 윤석진 해양수산연구사

어업인이 조업 중 말로 남긴 내용을 스마트폰에서 녹음·전사하고, 조사 항목 초안을 만든 뒤 담당자가 PC에서 원음과 대조해 확정하는 시스템입니다.

## 검토자 확인 순서

1. 저장소를 내려받습니다: `git clone https://github.com/syoon-lab/nifs-fishing-log-poc.git`
2. PC 자동 시험을 실행합니다: `cd pc-db && python3 -m unittest discover -s . -p 'test_*.py'`
   - 이 저장소만으로 213개 중 162개 통과, 51개 건너뜀(시험 자료 미포함).
3. PC 검토 화면을 엽니다: `python3 app.py serve --port 8771` 후 브라우저에서 <http://127.0.0.1:8771>
4. 앱을 빌드하려면 아래 "이 저장소에 넣지 않은 것"의 모델과 whisper.cpp를 먼저 받습니다.

## GitLab 이관 시 참고

- 대용량 파일(음성인식 모델, whisper.cpp)은 저장소 밖에 두고 받는 위치와 SHA-256을 문서로 관리합니다.
- 어업인 원자료와 시험 자료는 기관 내부에 보관하고 저장소에는 넣지 않습니다.
- Google Drive 자동 업로드는 앱 패키지 이름과 서명 키로 등록한 OAuth 클라이언트가 필요합니다. 서명 키가 바뀌면 다시 등록해야 합니다.
- PC 재전사·재추출용 모델 경로는 `pc-db/models.json`에서 이관 환경에 맞게 바꿉니다.

## 구성

| 폴더 | 내용 |
|---|---|
| `android-recorder/` | 스마트폰 앱 「조업기록」(Android, Java·C++) |
| `pc-db/` | 담당자 PC 검토 프로그램(Python, 로컬 브라우저 화면, SQLite) |

자료 흐름은 다음과 같습니다.

1. 스마트폰: 출항, 작업별 녹음, 입항 순서로 기록합니다.
2. 스마트폰: 기기 안에서 음성을 글로 바꾸고 항목 초안을 만든 뒤 빠진 항목을 다시 묻습니다.
3. 스마트폰: 입항 후 통신이 연결되면 기록 묶음(ZIP)을 Google Drive에 올립니다.
4. PC: ZIP을 가져와 원음과 대조하고 수정한 뒤 담당자가 확정합니다.
5. PC: 확정한 기록을 JSON·CSV로 내보냅니다.

## 이 저장소에 넣지 않은 것

| 제외한 것 | 이유 | 받는 방법 |
|---|---|---|
| 어업인 녹음, 위치, 조업 DB, 기록 ZIP | 개인정보와 비공개 원자료 | 기관 내부 보관 |
| 앱 음성인식 모델 `ggml-large-v3-turbo-q5_0.bin` (약 550MB) | 용량 | [whisper.cpp 모델 저장소](https://huggingface.co/ggerganov/whisper.cpp)에서 받아 `android-recorder/app/src/main/assets/models/`에 둠 |
| whisper.cpp 라이브러리 | 외부 공개 코드 | v1.8.7을 받아 `android-recorder/개발의존성/whisper.cpp/`에 둠 |
| PC 음성인식 모델(Whisper large-v3, 방언 학습 모델), 로컬 LLM(Qwen) | 용량, 워크스테이션 전용 | `pc-db/models.json`의 경로를 자기 환경에 맞게 고침 |
| 앱 설치 파일(APK), 빌드 결과물 | 다시 만들 수 있음 | 아래 빌드 방법 참고 |
| 개발 중 검증 기록(`검증자료/` 폴더) | 원자료가 섞여 있음 | 기관 내부 보관 |
| 시험 자료(시험 기기 내보내기 ZIP, 앱 시험용 음성·DB) | 시험 위치·음성 포함 | 기관 내부 보관 |

모델 파일의 정확한 버전과 SHA-256은 `android-recorder/app/src/main/assets/licenses/dependencies.json`에 있습니다.

## 스마트폰 앱 빌드

필요한 환경

- Java 17 이상
- Android SDK 36, Build Tools 36.0.0
- NDK 28.2.13676358, CMake 3.22.1
- ARM64 Android 8.0 이상 휴대전화(시험 기기: Galaxy S24)

순서

1. 위 표대로 whisper.cpp와 앱 모델 파일을 둡니다.
2. `android-recorder/local.properties`에 Android SDK 경로를 적거나 Android Studio에서 폴더를 엽니다.
3. 빌드합니다.

```sh
cd android-recorder
./gradlew assembleDebug
```

자동 시험(실기기 또는 가상기기 연결 필요)

```sh
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=kr.go.nifs.fieldrecorder.StorageTest,kr.go.nifs.fieldrecorder.WorkflowTest
```

- `EmulatorWorkflowTest`는 시험 자료를 추가하므로 폐기 가능한 가상기기에서만 실행합니다.
- 앱 시험이 읽는 `app/src/androidTest/assets/`는 저장소에 넣지 않았습니다. 이 폴더가 필요한 시험은 기관 내부 자료로 실행합니다.
- Google Drive 자동 업로드를 쓰려면 Google Cloud에서 이 앱의 패키지 이름과 서명으로 OAuth 클라이언트를 등록해야 합니다.

## PC 검토 프로그램 실행

```sh
cd pc-db
python3 app.py serve --port 8771
```

브라우저에서 <http://127.0.0.1:8771>을 엽니다. macOS에서는 `실행.command`를 눌러도 됩니다.

- 화면 조회, 가져오기, 확인·확정, 내보내기는 Python 표준 라이브러리만으로 동작합니다.
- PC 재전사와 항목 재추출에는 GPU 워크스테이션의 Whisper 모델과 Ollama가 필요합니다. 경로는 `pc-db/models.json`에서 바꿉니다.

자동 시험

```sh
cd pc-db
python3 -m unittest discover -s . -p 'test_*.py'
```

- 시험 기기에서 내보낸 시험 자료는 저장소에 넣지 않았습니다. 이 자료가 필요한 통합 시험은 자동으로 건너뜁니다.
- 이 저장소만으로 213개 중 162개가 통과하고 51개는 건너뜁니다(2026. 11. 25. 확인). 내부 자료가 있으면 213개가 모두 통과합니다.

## 자세한 안내

- 스마트폰 앱: [android-recorder/README.md](android-recorder/README.md)
- PC 검토 프로그램: [pc-db/README.md](pc-db/README.md)

두 README에는 개발 경과가 날짜별로 함께 적혀 있습니다. 문서 안의 `검증자료/` 링크는 이 저장소에 포함하지 않았습니다.

## 자료 보존 원칙

- 원래 녹음, 자동 정리 결과, 수정 이력은 지우거나 덮어쓰지 않습니다.
- 말하지 않은 값은 자동으로 채우지 않고, 빈 값을 0으로 바꾸지 않습니다.
- 키, 비밀번호, 개인정보, 원자료는 저장소에 올리지 않습니다.
