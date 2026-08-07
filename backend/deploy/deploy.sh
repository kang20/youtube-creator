#!/usr/bin/env bash
# 블루-그린 무중단 배포 — deploy-backend.yml 이 scp 후 앱 VM ~/ytcreator 에서 실행한다.
#   ① 대기 색을 새 이미지로 기동 → ② 헬스 UP 확인 → ③ Caddy 를 활성 색으로 전환 → ④ 구 색 드레인 후 정지.
#   헬스 실패 시 전환 없이 중단(기존 색/레거시가 계속 서빙). 모니터링(:8080 터널)은 무변경.
# 진행 상황은 디스코드(DEPLOY_WEBHOOK_URL)로 단계별 알림 — 시작 / 헬스체크 / 구 버전 셧다운 / 최종 결과(+실패 사유).
# 컨테이너 이름은 "ytcreator-app-<색>-<sha7>" — docker ps 만으로 어떤 커밋이 서빙 중인지 보인다.
# .env(IMAGE_TAG 포함)는 워크플로가 미리 생성해 둔다. compose 가 같은 디렉토리의 .env 를 자동 로드.
set -euo pipefail
cd ~/ytcreator
COMPOSE="docker compose -f docker-compose.prod.yml"

# .env 소싱 — 로그에 실제 IMAGE_TAG 반영(compose 는 별도로 .env 자동 로드).
set -a; [ -f .env ] && . ./.env; set +a

# compose container_name 접미사 — 새 색 컨테이너 이름에 커밋 sha 앞 7자리를 붙인다.
export SHORT_SHA="${IMAGE_TAG:-latest}"; SHORT_SHA="${SHORT_SHA:0:7}"

KIND="${DEPLOY_KIND:-배포}"                    # rollback.sh(specific-sha)가 "롤백(specific-sha)" 로 덮어쓴다
LOG_HINT="${RUN_URL:+ · 로그: ${RUN_URL}}"     # 워크플로가 .env 에 Actions 런 URL 을 넣어 준다

# ── 디스코드 알림 — 베스트 에포트: 알림 실패가 배포를 막아선 안 된다 ─────────────
notify() {
  [ -n "${DEPLOY_WEBHOOK_URL:-}" ] || return 0
  local msg="$1"
  msg="${msg//\\/\\\\}"; msg="${msg//\"/\\\"}"                       # JSON 이스케이프(역슬래시 → 따옴표 순서)
  msg="${msg//$'\r'/}"; msg="${msg//$'\n'/\\n}"; msg="${msg//$'\t'/ }"
  curl -sS -m 5 -H 'Content-Type: application/json' \
    -d "{\"content\":\"${msg}\"}" "$DEPLOY_WEBHOOK_URL" >/dev/null 2>&1 || true
}

# 예상치 못한 실패(pull 오류 등)도 결과 알림을 보장 — 상세 알림을 이미 보낸 경로는 NOTIFIED=1 로 중복 방지.
STAGE="준비"; NOTIFIED=0
on_exit() {
  local code=$?
  if [ "$code" -ne 0 ] && [ "$NOTIFIED" -eq 0 ]; then
    notify "❌ **${KIND} 실패** — 단계: ${STAGE} (exit ${code}), tag \`${IMAGE_TAG:-latest}\`${LOG_HINT}"
  fi
}
trap on_exit EXIT

color_port() { case "$1" in blue) echo 8081 ;; green) echo 8082 ;; esac; }
# 이름 규칙: ytcreator-app-<색>[-<sha7>|-latest] — 접미사 없는 구형 이름도 이행기 동안 함께 매칭한다.
name_re() { echo "^ytcreator-app-$1(-[0-9a-fA-F]{7}|-latest)?$"; }
cname_running() { docker ps --format '{{.Names}}' | grep -E -m1 "$(name_re "$1")" || true; }
container_exists() { docker ps -a --format '{{.Names}}' | grep -q "^$1$"; }
running_color() {
  if   [ -n "$(cname_running blue)"  ]; then echo blue
  elif [ -n "$(cname_running green)" ]; then echo green
  fi
}

# 헬스 게이트 — $1 포트가 UP 될 때까지 최대 120초(2s×60) 폴링
health_gate() {
  local port="$1" _
  for _ in $(seq 1 60); do
    if curl -fsS "http://127.0.0.1:${port}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 2
  done
  return 1
}

ACTIVE="$(running_color)"
ACTIVE_NAME=""; [ -n "$ACTIVE" ] && ACTIVE_NAME="$(cname_running "$ACTIVE")"
if   [ -z "$ACTIVE" ];      then NEW=blue          # 부트스트랩/이행 (실행 중인 색 없음)
elif [ "$ACTIVE" = blue ];  then NEW=green
else                             NEW=blue
fi
NEW_PORT="$(color_port "$NEW")"
NEW_NAME="ytcreator-app-${NEW}-${SHORT_SHA}"          # compose container_name 과 같은 규칙
LEGACY=""; container_exists ytcreator-app && LEGACY=1 # 블루-그린 이전의 단일 컨테이너(8080 점유)
SERVING="${ACTIVE_NAME:-없음}"; [ -n "$LEGACY" ] && SERVING="ytcreator-app(레거시)"
echo ">> active=${ACTIVE:-<none>}(${ACTIVE_NAME:--})  new=$NEW(port $NEW_PORT, $NEW_NAME)  legacy=${LEGACY:-0}  tag=${IMAGE_TAG:-latest}"

notify "🚀 **${KIND} 시작** — tag \`${IMAGE_TAG:-latest}\`
새 버전 \`${NEW_NAME}\` 기동 · 현재 서빙: \`${SERVING}\`${LOG_HINT}"

# Caddy 가 import 하려면 upstream.caddy 가 먼저 있어야 한다(최초 부트스트랩).
[ -f caddy/upstream.caddy ] || echo "reverse_proxy app-$NEW:8080" > caddy/upstream.caddy

# ① 대기 색 기동 — 트래픽은 계속 기존 색(또는 레거시)이 받는다.
STAGE="① 대기 색 기동(pull·up)"
$COMPOSE --profile "$NEW" pull "app-$NEW"
$COMPOSE --profile "$NEW" up -d "app-$NEW"

# ② 헬스 게이트 — UP 안 되면 전환 없이 실패(기존 색/레거시 무손상 유지).
STAGE="② 헬스 게이트"
if ! health_gate "$NEW_PORT"; then
  echo "::error:: $NEW_NAME 헬스체크 실패 — 트래픽 전환 없이 배포 중단 (기존 색/레거시 유지)"
  docker logs --tail 100 "$NEW_NAME" || true
  # 실패 사유 추출 — 에러 로그 우선, 없으면 마지막 로그. 제어문자 제거 + 디스코드 한도 대비 600자 절단.
  REASON="$(docker logs --tail 200 "$NEW_NAME" 2>&1 | grep -iE 'error|exception|caused by' | tail -n 5 || true)"
  [ -n "$REASON" ] || REASON="$(docker logs --tail 5 "$NEW_NAME" 2>&1 || true)"
  REASON="$(printf '%s' "$REASON" | tr -d '\000-\010\013\014\016-\037' | head -c 600)"
  # head -c 는 바이트 절단 — UTF-8(한글) 문자 중간이 잘리면 JSON 전체가 invalid → 깨진 꼬리 제거
  RC="$(printf '%s' "$REASON" | iconv -f UTF-8 -t UTF-8 -c 2>/dev/null || true)"; [ -n "$RC" ] && REASON="$RC"
  docker stop "$NEW_NAME" >/dev/null 2>&1 || true
  notify "❌ **${KIND} 실패 — 헬스체크 타임아웃(120초)** \`${NEW_NAME}\`
트래픽 전환 없이 중단 → 기존 버전 \`${SERVING}\` 이 계속 서빙 중(자동 보호)
사유(로그 발췌):
\`\`\`
${REASON:-로그 없음}
\`\`\`${LOG_HINT}"
  NOTIFIED=1
  exit 1
fi
notify "💚 **헬스체크 통과** — \`${NEW_NAME}\` UP → 트래픽 전환"

# ③ 트래픽 전환 — upstream 스니펫을 새 색으로 교체 후 Caddy 반영.
STAGE="③ 트래픽 전환(caddy)"
echo "reverse_proxy app-$NEW:8080" > caddy/upstream.caddy

if ! container_exists ytcreator-caddy; then
  # 완전 신규 VM — caddy 최초 기동.
  $COMPOSE up -d caddy
elif [ -n "$LEGACY" ]; then
  # 최초 이행 — 레거시 ytcreator-app 이 8080 을 점유 중이라 caddy 가 8080 을 인수하려면 먼저 비워야 한다.
  # 여기서만 수 초 공백(레거시 caddy 502 → 새 caddy 서빙). 이후 배포부터는 무중단(reload).
  echo ">> 최초 이행 — 레거시 제거 후 caddy 재생성(8080 인수, 수 초 공백)"
  docker rm -f ytcreator-app >/dev/null 2>&1 || true
  $COMPOSE up -d caddy
  notify "🌙 **이전 버전 종료** — 레거시 \`ytcreator-app\` 제거, caddy 가 8080 인수(최초 이행 — 이번만 수 초 공백)"
else
  # 정상 배포 — caddy 는 재생성하지 않는다(정의 불변). 그레이스풀 reload 로 무중단 전환.
  docker exec -w /etc/caddy ytcreator-caddy caddy reload --config /etc/caddy/Caddyfile
fi

# ④ 구 색 드레인 후 정지 — graceful shutdown 이 처리 중 요청을 마치고 종료.
#    컨테이너는 삭제하지 않고 정지 상태로 남긴다(rollback previous 자원).
STAGE="④ 구 색 드레인·정지"
if [ -n "$ACTIVE" ] && [ "$ACTIVE" != "$NEW" ]; then
  sleep 5
  docker stop "$ACTIVE_NAME" >/dev/null 2>&1 || true
  notify "🌙 **이전 버전 종료** — \`${ACTIVE_NAME}\` 그레이스풀 셧다운(드레인 완료 · rollback previous 자원으로 보존)"
fi

# ⑤ 이미지 정리 — 컨테이너(실행/정지)가 참조하는 이미지는 보호(정지 색=롤백 자원),
#    그 외 옛 sha 태그 백엔드 이미지만 제거해 12GB 디스크 누적을 막는다.
#    정리는 배포 성공 여부와 무관하므로 절대 스크립트를 실패시키지 않는다(set +e 국소화).
STAGE="⑤ 이미지 정리"
cleanup_images() {
  local used_refs used_ids ref id
  used_refs="$(docker ps -a --format '{{.Image}}')"
  # docker ps 에는 이미지 ID 필드가 없다 → 컨테이너 inspect 로 참조 이미지 sha 를 모은다.
  used_ids="$(docker ps -aq | xargs -r docker inspect -f '{{.Image}}' 2>/dev/null | sed 's/^sha256://')"
  docker images 'ghcr.io/kang20/ytcreator-backend' --format '{{.Repository}}:{{.Tag}} {{.ID}}' \
    | while read -r ref id; do
        printf '%s\n' "$used_refs" | grep -qxF "$ref" && continue
        [ -n "$used_ids" ] && printf '%s\n' "$used_ids" | grep -qF "${id#sha256:}" && continue
        docker rmi "$ref" >/dev/null 2>&1 || true
      done
  docker image prune -f >/dev/null 2>&1 || true
}
( set +e; cleanup_images ) || true

echo ">> 배포 완료: $NEW 활성 ($NEW_NAME, tag ${IMAGE_TAG:-latest})"
notify "✅ **${KIND} 성공** — 현재 서빙: \`${NEW_NAME}\` (tag \`${IMAGE_TAG:-latest}\`)"
NOTIFIED=1
