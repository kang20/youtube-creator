# ytcreator 모니터링 온보딩 — 기존 모니터링 서버에 이 프로젝트 붙이기

> 모니터링 스택(Prometheus·Grafana·Loki·Blackbox)은 **새로 만들지 않는다.**
> 이미 24/7 돌고 있는 서버(`rasp4`)에 이 프로젝트를 **얹기만** 한다.
> 이 디렉토리의 파일들은 전부 그 서버로 복사할 조각이다.

## 이 프로젝트가 쓰는 포트 블록

| 대상 | 모니터링 서버 로컬 포트 | 원격 |
|---|---|---|
| 앱 actuator | `20200` | 앱 VM `127.0.0.1:8080` (Caddy 루프백 사이트) |
| 앱 node_exporter | `20201` | 앱 VM `127.0.0.1:9100` |
| DB mysqld_exporter | `20202` | DB VM `__DB_PRIVATE_IP__:9104` (앱 VM 경유) |
| DB node_exporter | `20203` | DB VM `__DB_PRIVATE_IP__:9100` (앱 VM 경유) |
| 로그 push | `3100` (역방향) | 앱 VM Alloy → Pi Loki |

> 포트 블록이 다른 프로젝트와 겹치지 않는지 먼저 확인:
> `ssh rasp4 'ls ~/monitoring/prometheus/targets/'`

## 0. 최초 1회 (모니터링 서버에 아직 file_sd 가 없다면)

`prometheus-snippet.yml` 의 4개 잡을 `~/monitoring/prometheus/prometheus.yml` 에 추가하고,
compose 의 prometheus 서비스에 `./prometheus/targets:/etc/prometheus/targets:ro` 마운트를 더한다.
이 작업은 **모니터링 서버 생애 통틀어 한 번**이고, 이후 프로젝트는 1~4단계만 하면 된다.

```bash
ssh rasp4 'mkdir -p ~/monitoring/prometheus/targets'
# prometheus.yml · docker-compose.yml 수정 후
ssh rasp4 'cd ~/monitoring && docker compose up -d prometheus'
```

## 1. 앱 VM 에 Pi 공개키 등록 (역터널 -R 3100 의 전제)

```bash
ssh rasp4 'cat ~/.ssh/id_ed25519.pub'
# 출력값을 앱 VM 의 ~/.ssh/authorized_keys 에 추가
```

## 2. 터널 유닛 설치

```bash
scp ytcreator-tunnel.service rasp4:~/
ssh rasp4 'sudo cp ~/ytcreator-tunnel.service /etc/systemd/system/ \
  && sudo systemctl daemon-reload && sudo systemctl enable --now ytcreator-tunnel'
```

`<PI_USER>` · `<APP_VM_HOST>` 를 먼저 채운다(파일 주석 참고). 검증:

```bash
ssh rasp4 'for p in 20200 20201 20202 20203; do
  printf "%s: " "$p"; curl -s -o /dev/null -w "%{http_code}\n" "localhost:$p/metrics" || echo FAIL; done'
```

## 3. 스크레이프 타깃 등록 (파일 복사만 — 재시작 불필요)

```bash
scp targets/*.yml rasp4:~/monitoring/prometheus/targets/
# 30초 뒤 확인
ssh rasp4 'curl -s localhost:9090/api/v1/targets | grep -o "\"project\":\"ytcreator\"" | wc -l'
```

## 4. 알림 룰 등록

```bash
scp alerting/rules-ytcreator.yml rasp4:~/monitoring/grafana/provisioning/alerting/
ssh rasp4 'cd ~/monitoring && docker compose restart grafana'
```

- contact point(디스코드)·notification policy 는 **기존 것을 그대로 쓴다.** 새로 만들지 않는다.
- 룰은 `folder: ytcreator` 로 격리돼 다른 프로젝트와 섞이지 않는다.
- 알림 문구·라벨에 `project: ytcreator` 가 들어가 어느 서비스인지 바로 보인다.

## 5. 대시보드

기존 대시보드(JVM Micrometer 4701 · MySQL Overview 14057 · Node Exporter Full 1860)를 그대로 쓰되,
**대시보드 변수 `project`** 를 추가해 프로젝트를 골라 보게 한다.

- Grafana → 대시보드 → Settings → Variables → New: `Query` / `label_values(up, project)`
- 각 패널 쿼리의 셀렉터에 `project="$project"` 추가

로그(Loki)는 `{project="ytcreator"}` 로 조회한다. Alloy 가 이 라벨을 붙여 push 한다.

## 체크리스트

- [ ] 포트 블록 중복 없음 확인
- [ ] Pi 공개키가 앱 VM authorized_keys 에 등록
- [ ] 터널 유닛 active + 4개 포트 curl 성공
- [ ] Prometheus 타깃 4개 `up=1`
- [ ] Grafana 알림 룰 폴더 `ytcreator` 생성 확인
- [ ] Loki 에서 `{project="ytcreator"}` 로그 조회됨
- [ ] 앱을 일부러 내려 AppDown 알림이 디스코드로 오는지 1회 실측
