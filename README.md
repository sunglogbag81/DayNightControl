# DayNightControl

Paper/Spigot 계열 서버에서 월드의 낮/밤 길이를 마음대로 바꾸는 플러그인입니다.

## 빌드

```bash
./gradlew build
```

완성 JAR:

```text
build/libs/DayNightControl-1.0.0.jar
```

## 설치

1. JAR 파일을 서버 `plugins/` 폴더에 넣습니다.
2. 서버를 재시작합니다.
3. `/dnc set day <분>` 또는 `/dnc set night <분>`으로 조정합니다.

## 명령어

- `/dnc status` — 현재 월드 설정 확인
- `/dnc set day <minutes> [world]` — 낮 길이 변경
- `/dnc set night <minutes> [world]` — 밤 길이 변경
- `/dnc set enabled <true|false> [world]` — 월드별 켜기/끄기
- `/dnc reload` — config.yml 다시 읽기
- `/dnc help` — 도움말

권한: `daynightcontrol.admin`

## 예시

```text
/dnc set day 30
/dnc set night 5
/dnc set day 20 world_nether
```

## 동작 방식

서버 틱마다 월드 시간을 조금씩 직접 증가시킵니다.
기본값은 낮 10분 / 밤 10분이며, gamerule `doDaylightCycle`은 플러그인이 자동으로 꺼서 바닐라 시간 흐름과 충돌하지 않게 합니다.
