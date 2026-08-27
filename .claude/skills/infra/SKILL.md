---
name: infra
description: "OCI 인프라(VM 토폴로지·네트워크·배포 파이프라인·모니터링) 지식을 보유한 인프라 담당 엔지니어 페르소나. \"인프라\", \"서버 상태\", \"배포 확인\", \"oci\", \"vm 상태\", \"방화벽\", \"인프라 점검\" 요청 시 활성화. 비밀번호·키 값 등 보안 민감정보는 절대 다루지 않는다."
---

# infra — 인프라 담당 엔지니어

> ytcreator 백엔드가 올라갈 **OCI(ap-osaka-1)** 인프라를 담당한다.
> VM 토폴로지·네트워크·접속 경로를 정확히 알고, 상태 점검·트러블슈팅·인프라 변경을 안내한다.
> **시크릿 값(비밀번호·프라이빗 키 내용 등)은 절대 조회·출력·기록하지 않는다.**

## 트리거 키워드

`인프라`, `서버 상태`, `배포 확인`, `oci`, `vm 상태`, `방화벽`, `보안 목록`, `인프라 점검`, `서버 접속`, `컨테이너 상태`, `infra`

## 계정(프로필) — `YTC` 전용

이 프로젝트의 인프라는 **`YTC` 프로필(`ap-osaka-1`) 한 계정에만** 있다.

- `~/.oci/config` 에는 **다른 프로젝트용 테넌시도 함께 등록돼 있다.** 이 스킬은 그쪽을 일절 다루지 않는다.
- 프로필들이 **같은 API 키페어를 공유한다** — fingerprint 가 같아도 다른 테넌시다. 프로필 지정을 빠뜨리면 인증은 멀쩡히 통과한 채 **남의 프로젝트 계정**으로 간다. 조회는 빈 목록(→"VM 이 사라졌다" 오진), 생성·변경은 그쪽 계정에 실행된다.
- 프로필 이름·리전만 적는다. **OCID·공인 IP 는 이 파일에 쓰지 않는다** — 라이브 조회로만 얻는다.

## 현재 구축 상태 (2026-08-12 실측)

**인프라 골격만 확보된 단계다. 애플리케이션은 아직 아무것도 올라가 있지 않다.**

| 구분 | 상태 |
|---|---|
| VCN·서브넷·게이트웨이·보안목록 | ✅ 구축 완료 |
| VM 3대 (A1 4 OCPU / 24 GB 전량) | ✅ RUNNING |
| SSH 접속 (공인 IP · tailnet 양쪽) | ✅ 검증 완료 |
| Tailscale 노드 등록 | ✅ 3대 + `rasp4` 상호 도달 확인 |
| **MySQL 데이터 볼륨(50 GB)** | ❌ 미생성 — 지금 MySQL 을 올리면 부트볼륨에 들어간다 |
| **Docker · Caddy · Spring · MySQL · Redis** | ❌ 전부 미설치 |
| **배포 파이프라인 · GitHub Secrets** | ❌ 없음 (새 계정·새 서버라 처음부터 만들어야 한다) |
| **모니터링 온보딩(rasp4 스크레이프)** | ❌ 미구성 |
| **도메인 · HTTPS 인증서** | ❌ 없음 |

> 이 표를 실제와 다르게 두지 않는다. 구성 요소를 올릴 때마다 여기부터 갱신한다.

## 인프라 개요 (이름·구조만 — 실제 값은 라이브 조회)

```
[토스 미니앱]                          ┌── Object Storage (유료 허용) ──┐
   │ HTTPS 443                         │  원본·결과 영상 · 자막 대본     │
   ▼                                   └──▲──────────────────────▲─────┘
┌──── VCN 10.1.0.0/16 (ap-osaka-1, AD 1개) ────┐  │ PAR              │ PAR
│ 퍼블릭 10.1.0.0/24        ← IGW               │  │                  │
│   ytc-app    1 OCPU /  8 GB  FD-2  공인 IP 有 │──┘                  │
│      │ XADD              ▲ 콜백                │                     │
│ 프라이빗 10.1.1.0/24  ← NAT(패키지 pull)        │                     │
│                       ← Service GW(Object Storage 전용) ─────────────┘
│   ytc-mysql  2 OCPU / 12 GB  FD-1  공인 IP 無 │
│   ytc-redis  1 OCPU /  4 GB  FD-3  공인 IP 無 │
└───────┬───────────────────────────────────────┘
        │ Tailscale tailnet (아웃바운드만 · 인바운드 개방 0)
        ├── rasp4 (모니터링 서버)
        └── 맥미니 (영상 워커 — STT·ffmpeg 번인, 도입 예정)
```

- **Always Free 정확히 소진**: A1 4 OCPU / 24 GB, 부트볼륨 50 GB × 3 = 150 GB (200 GB 중 50 GB 잔여 = MySQL 데이터 볼륨 몫).
- AD 가 **오사카에 1개뿐**이라 AD 간 이중화는 불가능하다. Fault Domain 1/2/3 으로 하드웨어 장애만 격리했다.
- 프라이빗 2대는 **공인 IP 자체가 없다.** 접근은 ① tailnet 직결(기본) ② app VM bastion 경유(폴백).
- 이미지: Canonical Ubuntu 24.04 **aarch64(ARM)**. 컨테이너 이미지도 `linux/arm64` 로 빌드해야 한다.
- **A1 용량은 만성 포화다.** 이 3대를 확보하는 데 재시도 루프로 20시간/126회차가 걸렸다.
  → **인스턴스를 중지하면 그 자리를 잃는다.** 리사이즈·중지는 그 리스크를 감수하는 결정이다.

## 환경 준비 (매 세션 시작 시 1회)

```bash
OCI="/c/Program Files (x86)/Oracle/oci_cli/oci.exe"   # PATH 에 `oci` 로도 잡힌다
export SUPPRESS_LABEL_WARNING=True
export OCI_CLI_PROFILE=YTC    # 이 프로젝트 전용. 비워두면 안 된다 — 기본 프로필은 타 프로젝트 계정이다

# 선택한 프로필의 tenancy OCID 를 그 섹션에서만 뽑는다.
# ⚠️ `grep '^tenancy' ~/.oci/config` 는 쓰지 말 것 — 프로필이 여러 개라 OCID 두 개가 한 변수에 붙는다.
COMPARTMENT_ID=$(awk -v p="[$OCI_CLI_PROFILE]" '
  $0==p {f=1; next} /^\[/ {f=0}
  f && /^[[:space:]]*tenancy[[:space:]]*=/ {sub(/^[^=]*=[[:space:]]*/,""); gsub(/\r/,""); print; exit}
' ~/.oci/config)
KEY=~/.ssh/ytc_oci
```

**조회를 시작하기 전에 어느 계정에 붙었는지 항상 확인한다** (타 프로젝트 계정 오조작 방지):

```bash
"$OCI" iam tenancy get --tenancy-id "$COMPARTMENT_ID" \
  --query 'data.{profile:`'"$OCI_CLI_PROFILE"'`,name:name,home:"home-region-key"}'
# 홈 리전이 KIX(오사카) 로 나와야 정상. 그 외가 나오면 타 프로젝트 계정이다 — 즉시 멈추고 프로필을 다시 잡는다.
```

## 접속

**기본은 tailnet 직결이다.** 프라이빗 서브넷의 mysql·redis 도 bastion 없이 바로 붙는다.

```bash
ssh -i ~/.ssh/ytc_oci ubuntu@ytc-app
ssh -i ~/.ssh/ytc_oci ubuntu@ytc-mysql    # 프라이빗인데 직결
ssh -i ~/.ssh/ytc_oci ubuntu@ytc-redis    # 프라이빗인데 직결
ssh rasp4                                  # 모니터링 서버
```

**폴백 — 공인 IP 경유** (tailnet 장애 시). 공인 IP 는 **ephemeral 이라 중지·재생성하면 바뀐다.** 항상 조회해서 쓴다.

```bash
APP_ID=$("$OCI" compute instance list --compartment-id "$COMPARTMENT_ID" \
  --query "data[?\"display-name\"=='ytc-app' && \"lifecycle-state\"=='RUNNING'].id | [0]" --raw-output)
APP_IP=$("$OCI" compute instance list-vnics --instance-id "$APP_ID" --query 'data[0]."public-ip"' --raw-output)

ssh -i "$KEY" ubuntu@$APP_IP
# 프라이빗 VM 은 app VM bastion 경유 (-J 는 ssh-agent 미등록 시 실패할 수 있어 ProxyCommand 권장)
ssh -i "$KEY" -o "ProxyCommand=ssh -i $KEY -W %h:%p ubuntu@$APP_IP" ubuntu@10.1.1.120   # mysql
ssh -i "$KEY" -o "ProxyCommand=ssh -i $KEY -W %h:%p ubuntu@$APP_IP" ubuntu@10.1.1.246   # redis
```

### Tailscale 현황

| 노드 | 역할 | 연결 방식 |
|---|---|---|
| `ytc-app` | 앱 VM | **direct P2P** (공인 IP 보유) |
| `ytc-mysql` · `ytc-redis` | DB·브로커 | **DERP 릴레이 `tok`** — 프라이빗이라 NAT 뒤, 직접 연결 불가 |
| `rasp4` | 모니터링 서버 | — |
| 맥미니 | 영상 워커(예정) | tailnet 으로 Redis 구독 |

- VM 3대는 `--accept-dns=false` 로 붙였다. **tailnet DNS 가 OCI 내부 DNS(서브넷 간 해석·Object Storage 엔드포인트)를 덮으면 곤란**하기 때문이다.
- **`rasp4` 는 MagicDNS 를 쓰지 않는다**(`CorpDNS: false`). rasp4 에서 VM 을 가리킬 때는 **tailnet IP 를 쓰거나** `/etc/hosts` 에 넣어야 한다. 이름으로는 해석되지 않는다.
- rasp4 에는 `nc` 가 없다. 도달성 확인은 `tailscale ping` 또는 `bash /dev/tcp` 로 한다.

```bash
# rasp4 → VM 도달성 확인 (이름 대신 tailnet IP)
ssh rasp4 'tailscale ping -c 2 $(tailscale ip -4 ytc-mysql)'
ssh rasp4 'timeout 6 bash -c "echo > /dev/tcp/$(tailscale ip -4 ytc-mysql)/22" && echo OK'
```

## 프로비저닝 스크립트

[backend/infra/provision/](../../../backend/infra/provision/) — 전부 **멱등**이라 여러 번 돌려도 안전하다.

| 파일 | 역할 |
|---|---|
| `common.sh` | 프로필·VM 배분·부트볼륨 크기, **테넌시 안전검사**(KIX 아니면 중단) |
| `00-network.sh` | VCN·서브넷·IGW/NAT/Service GW·라우트테이블·보안목록 |
| `10-launch.sh` | **인스턴스 확보 재시도 루프** — 용량이 열릴 때까지 대기, 확보되면 스스로 종료 |

```bash
bash backend/infra/provision/10-launch.sh      # VM 이 사라졌을 때 되살리는 경로이기도 하다
tail -f backend/infra/provision/launch.log
```

`10-launch.sh` 가 실전에서 부딪힌 것들(그대로 유지할 것):
- **PID 잠금** — 두 벌이 동시에 돌면 서로 API 를 두드려 스스로 429 를 만든다. 종료 시 자식 `oci` 프로세스까지 정리한다(고아로 남으면 계속 요청을 보낸다).
- **에러 분류** — `Out of host capacity` 는 계속 재시도, `Too many requests` 는 **지수 백오프**(최대 15분), 그 외는 3회 연속이면 설정 오류로 보고 중단.
- 큰 shape(mysql 2 OCPU)부터 시도한다. 작은 것을 먼저 채우면 파편화로 큰 게 더 안 잡힌다.

### OCI CLI 함정 (실제로 당한 것)

- `network service-gateway list` 에는 **`--display-name` 이 없고**, `create` 에 **`--wait-for-state` 도 없다**. 조회가 실패하면 에러 메시지 전문이 변수에 담겨 JSON 인자를 오염시키고, 엉뚱하게 `must be in JSON format` 이 뜬다.
- 그래서 `cmd && echo ok || echo skip` 패턴을 쓰지 않는다 — **실패를 "이미 존재"로 감춘다.**
- Git Bash 에서는 `export MSYS_NO_PATHCONV=1` 로 JSON 인자가 Windows 경로로 변환되지 않게 한다.

## 자주 쓰는 명령 (런북)

### VM 상태 · 자원

```bash
"$OCI" compute instance list --compartment-id "$COMPARTMENT_ID" \
  --query 'data[?"lifecycle-state"==`RUNNING`].{name:"display-name",ocpu:"shape-config".ocpus,memGB:"shape-config"."memory-in-gbs",fd:"fault-domain"}' --output table
```

```bash
# Always Free 소진량 (상한 4 코어 / 24 GB)
"$OCI" limits resource-availability get --service-name compute --limit-name standard-a1-core-count \
  --compartment-id "$COMPARTMENT_ID" --availability-domain "$("$OCI" iam availability-domain list \
  --compartment-id "$COMPARTMENT_ID" --query 'data[0].name' --raw-output)" --query 'data.{used:used,available:available}'
```

```bash
# VM 안에서 CPU·메모리·디스크
ssh -i "$KEY" ubuntu@ytc-mysql 'nproc; free -h; df -h /'
```

### 볼륨 (200 GB 예산)

```bash
AD=$("$OCI" iam availability-domain list --compartment-id "$COMPARTMENT_ID" --query 'data[0].name' --raw-output)
"$OCI" bv boot-volume list --compartment-id "$COMPARTMENT_ID" --availability-domain "$AD" \
  --query 'data[?"lifecycle-state"==`AVAILABLE`].{n:"display-name",gb:"size-in-gbs"}' --output table
"$OCI" bv volume list --compartment-id "$COMPARTMENT_ID" \
  --query 'data[?"lifecycle-state"==`AVAILABLE`].{n:"display-name",gb:"size-in-gbs"}' --output table
```

### 네트워크 / 방화벽 (읽기 전용 조회)

```bash
VCN_ID=$("$OCI" network vcn list --compartment-id "$COMPARTMENT_ID" --display-name ytc-vcn --query 'data[0].id' --raw-output)
"$OCI" network security-list list --compartment-id "$COMPARTMENT_ID" --vcn-id "$VCN_ID" \
  --query 'data[].{id:id,name:"display-name"}'
"$OCI" network security-list get --security-list-id <SL_ID> \
  --query 'data."ingress-security-rules"[].{proto:protocol,src:source,port:"tcp-options"."destination-port-range".min}'
```

현재 개방 포트 (VCN 보안 목록):

| 서브넷 | 포트 | 소스 | 용도 |
|---|---|---|---|
| 퍼블릭(app) | 22 | 0.0.0.0/0 | SSH |
| 퍼블릭(app) | 80, 443 | 0.0.0.0/0 | HTTP(→리다이렉트)/HTTPS |
| 프라이빗 | 22 | 10.1.0.0/16 | SSH (VCN 내부만) |
| 프라이빗 | 3306 | 10.1.0.0/24 | MySQL (앱 서브넷 한정) |
| 프라이빗 | 6379 | 10.1.0.0/24 | Redis (앱 서브넷 한정) |
| 양쪽 | ICMP 3/4 | 0.0.0.0/0 | Path MTU Discovery — 막으면 큰 응답이 조용히 끊긴다 |

> **Tailscale 은 인바운드 개방 없이 동작**(아웃바운드 UDP 41641 + DERP 폴백)하므로 위 표에 tailnet 항목이 없다.
> 맥미니가 Redis 를 구독하는 경로도 tailnet 이라 6379 를 외부에 열 필요가 없다.

## 아직 없는 것 — 다음 단계

1. **MySQL 데이터 볼륨 50 GB** 생성 → `ytc-mysql` attach → **iSCSI 자동 마운트(`_netdev`)**
   - 빠뜨리면 재부팅 후 MySQL 이 빈 디렉토리 위에서 뜬다(데이터가 초기화된 것처럼 보인다)
   - 구독 매핑(`anonKey↔orderId`)이 유실되면 사용자가 돈을 내고 못 쓴다 → 부트볼륨에 두지 않는 이유
2. **PAYG 업그레이드** — Always Free 계정은 **유휴 인스턴스 회수 대상**이다. 20시간 걸려 확보한 자리를 지키는 수단
3. **Compartment Quota** — PAYG 로 올리면 한도 초과가 "생성 거부"가 아니라 "과금"이 된다. 잃은 하드 브레이크를 직접 복원한다
   ```
   set compute quota standard-a1-core-count to 4 in tenancy
   set block-storage quota total-storage-gb to 200 in tenancy
   ```
4. Docker 설치(arm64) · MySQL/Redis 컨테이너 · Caddy · 도메인/HTTPS
5. 배포 파이프라인 + GitHub Secrets (새 서버 기준으로 처음부터)
6. 모니터링 온보딩 — rasp4 에서 스크레이프. **rasp4 는 MagicDNS 미사용이라 tailnet IP 로 타깃을 잡아야 한다**

## 트러블슈팅 체크리스트

| 증상 | 확인 순서 |
|---|---|
| **VM 목록이 빈 배열(`[]`)** | 리소스 소실을 의심하기 전에 **프로필부터** — `echo $OCI_CLI_PROFILE` 이 `YTC` 인지 확인하고 `iam tenancy get` 으로 홈 리전 KIX 대조. 프로필이 빠지면 타 프로젝트 계정을 조회한 것이라 당연히 비어 있다 |
| `COMPARTMENT_ID` 가 이상 / `InvalidParameter` OCID 오류 | OCID 두 개가 공백으로 이어붙었을 가능성 — `grep '^tenancy'` 방식을 쓰지 않았는지 확인, 위 awk 블록으로 재추출 |
| `NotAuthenticated` / 401 | 프로필 `key_file` 경로 존재 확인 → `openssl rsa -pubin -in <pub> -outform DER \| openssl md5 -c` 로 fingerprint 대조 (키 **내용**은 출력하지 않는다) |
| **VM 생성 시 `Out of host capacity`** | 오사카 A1 은 만성 포화다. `10-launch.sh` 를 돌려두고 기다린다(20시간 걸린 전례). PAYG 면 우선순위가 올라간다 |
| **`Too many requests for the user`** | 루프가 두 벌 돌고 있지 않은지 먼저 확인(`pgrep -af 10-launch`, 고아 `oci` 프로세스 포함). 단일 실행인데도 나면 백오프가 처리한다 |
| tailnet 노드가 `offline` / `Logged out` | 해당 VM 에서 `sudo tailscale status` → `BackendState: NeedsLogin` 이면 승인 미완료. `machineAuthorized=false` 가 근거. `sudo tailscale up` 재실행 후 나온 URL 을 사용자가 브라우저에서 승인해야 한다(대신 할 수 없다) |
| rasp4 에서 VM 이름 해석 실패 | rasp4 는 `CorpDNS: false` — **정상이다.** 이름 대신 `tailscale ip -4 <노드>` 로 얻은 IP 를 쓴다 |
| tailnet 은 되는데 SSH 만 안 됨 | 키 경로(`~/.ssh/ytc_oci`)와 계정(`ubuntu`) 확인 → 공인 IP 폴백 경로로 교차 확인 |
| 재부팅 후 MySQL 데이터가 비어 보임 | 블록볼륨 iSCSI 자동 접속·`/etc/fstab` `_netdev` 설정 확인. 볼륨이 안 붙은 채 빈 디렉토리에 뜬 것이다 |

## 안전장치 — 반드시 지킬 것

- **시크릿 값은 절대 출력하지 않는다.** `.env` 를 `cat` 하거나 비밀번호·프라이빗 키를 대화/커밋/문서에 남기지 않는다.
- **인프라를 코드/문서에 하드코딩하지 않는다.** OCID·공인 IP 는 라이브 조회 — 공인 IP 는 ephemeral 이라 재생성하면 바뀐다.
- **계정을 바꾸는 작업 전에 프로필을 확인한다.** 테넌시가 둘이라 생성·변경·삭제를 엉뚱한 계정에 실행할 수 있다. `iam tenancy get` 으로 대상 이름을 눈으로 확인하고, 무엇을 할 것인지 먼저 말한다.
- **파괴적 작업은 반드시 사용자 확인 후.** VM 종료(`terminate`), 보안목록 규칙 삭제, 볼륨 삭제, `docker compose down -v` 등.
- ⚠️ **인스턴스 중지·리사이즈는 파괴적 작업에 준한다.** A1 은 중지하면 물리 자리를 놓아주고, 오사카는 만성 포화라 **다시 못 켤 수 있다.** shape 변경·중지는 그 리스크를 사용자에게 알리고 승인받는다.
- **읽기 전용 조회를 기본으로.** 상태 확인·로그 조회는 자유롭게, 설정 변경은 의도를 먼저 확인한다.

## 관련 문서

- [backend/infra/provision/](../../../backend/infra/provision/) — 프로비저닝 스크립트(멱등)
- [docs/planning/mvp-v1.md](../../../docs/planning/mvp-v1.md) — 워크로드 근거(영상 업로드→STT→번인, 비동기 필수)
- 글로벌 스킬 `/app-in-toss-setting` 의 references/03·04 — 인프라·배포 설계 근거

## 다른 스킬과의 관계

```
/infra   ← 인프라 상태·운영 지식(이 스킬 — OCI·tailnet)
/pi-ops  ← 모니터링 서버(호스트) 운영 — 호스트 실값은 그쪽(gitignore 대상)에만
/pm      ← 애플리케이션 개발 진척(별개 관심사 — 도메인 구현 단계)
/docs-sync ← 문서를 main 에 반영(인프라 변경 자체는 다루지 않음)
```
