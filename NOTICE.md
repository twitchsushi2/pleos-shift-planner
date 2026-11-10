# 출처와 라이선스 안내

교대 플래너가 쓰는 데이터와 외부 서비스, 오픈소스 라이브러리입니다.

## 앱에 들어 있는 데이터

### 고속도로망·휴게소 위치 — OpenStreetMap

- 파일: [`app/src/main/resources/expressway.tsv`](app/src/main/resources/expressway.tsv)
- 원자료: © OpenStreetMap contributors, https://www.openstreetmap.org/copyright
- 라이선스: [Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/)
- 만든 방법: [`tools/gen_expressway.py`](tools/gen_expressway.py)로 Overpass API에서 고속도로(`highway=motorway`)와 휴게소(`highway=services`)를 받아 도로망으로 이었습니다.
  그다음 [`tools/apply_official_rest_areas.py`](tools/apply_official_rest_areas.py)로 아래 공공데이터의 이름·편의시설을 덧붙였습니다.
- `expressway.tsv`는 OpenStreetMap에서 파생한 데이터베이스이므로 ODbL 1.0을 따릅니다.
  누구나 같은 조건으로 쓰고 고치고 나눌 수 있습니다. 앱 지도 화면 아래에 "© OpenStreetMap contributors"를 표시합니다.

### 휴게소 이름·편의시설 — 한국도로공사

- 자료: 「전국휴게소정보표준데이터」(한국도로공사), 공공데이터포털 https://www.data.go.kr/data/15025446/standard.do
- 기준일: 2026-09-21 수정본
- 이용허락범위: 제한 없음

## 실행 중에 쓰는 외부 서비스

| 서비스 | 용도 | 보내는 것 | 출처 표시 |
|---|---|---|---|
| [OpenFreeMap](https://openfreemap.org) | 지도 타일·스타일 | 보이는 지도 영역 | © OpenFreeMap, © OpenMapTiles, © OpenStreetMap contributors |
| [OSRM](https://project-osrm.org) 공개 서버 | 고속도로를 안 타는 짧은 경로의 미리보기 선 | 출발지·목적지 좌표 | 경로 OSRM |
| [Photon](https://photon.komoot.io) (komoot) | 장소 이름 검색 | 검색어, 차의 대략적인 위치 | © OpenStreetMap contributors |

운전자 이름이나 차량 정보는 어디에도 보내지 않습니다.

지도 스타일에는 [Natural Earth](https://www.naturalearthdata.com)(퍼블릭 도메인)와
[OpenMapTiles](https://openmaptiles.org)(CC-BY 4.0) 자료가 들어 있습니다.

## 오픈소스 라이브러리

| 라이브러리 | 라이선스 |
|---|---|
| [MapLibre Native for Android](https://github.com/maplibre/maplibre-native) | BSD 2-Clause |
| AndroidX, Jetpack Compose, Kotlin, Kotlin Coroutines | Apache License 2.0 |
| Pleos SDK (Vehicle, Navi, TTS/STT) | Pleos 개발자 이용 약관 |
