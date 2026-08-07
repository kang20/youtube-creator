#!/usr/bin/env bash
# 롤백 — rollback.yml(GitHub Actions)이 SSH 로 앱 VM ~/ytcreator 에서 실행한다.
#   rollback.sh previous            → 정지 상태로 남은 직전 색 컨테이너를 되살려 전환 (수 초)
#   rollback.sh specific-sha <sha>  → 해당 커밋 이미지로 재배포(deploy.sh 무중단 경로, 1~2분)
# .env(IMAGE_TAG 포함)는 rollback.yml 이 미리 생성한다(specific-sha 면 IMAGE_TAG=<sha>).
# 진행/결과는 배포와 같은 디스코드 웹훅(DEPLOY_WEBHOOK_URL)으로 알림(실패 사유 포함).
set -euo pipefail
cd ~/ytcreator
TARGET="${1:-previous}"
SHA="${2:-}"

# DEPLOY_WEBHOOK_URL·RUN_URL 등 알림 설정 로드 — 없으면 알림만 생략(롤백은 계속 동작).
set -a; [ -f .env ] && . ./.env; set +a
LOG_HINT="${RUN_URL:+ · 로그: ${RUN_URL}}"

# deploy.sh 와 동일한 헬퍼 중복 — 롤백은 비상 경로라 파일 하나로 자급자족한다(공유 lib 없음).
notify() {
  [ -n "${DEPLOY_WEBHOOK_URL:-}" ] || return 0
  local msg="$1"
  msg="${msg//\\/\\\\}"; msg="${msg//\"/\\\"}"
  msg="${msg//$'\r'/}"; msg="${msg//$'\n'/\\n}"; msg="${msg//$'\t'/ }"
  curl -sS -m 5 -H 'Content-Type: application/json' \
    -d "{\"content\":\"${msg}\"}" "$DEPLOY_WEBHOOK_URL" >/dev/null 2>&1 || true
}

STAGE="준비"; NOTIFIED=0
on_exit() {
  local code=$?
  if [ "$code" -ne 0 ] && [ "$NOTIFIED" -eq 0 ]; then
    notify "❌ **롤백 실패** — 단계: ${STAGE} (exit ${code})${LOG_HINT}"
  fi
}
trap on_exit EXIT

color_port() { case "$1" in blue) echo 8081 ;; green) echo 8082 ;; esac; }
# 이름 규칙: ytcreator-app-<색>[-<sha7>|-latest] — 접미사 없는 구형 이름도 이행기 동안 함께 매칭한다.
name_re() { echo "^ytcreator-app-$1(-[0-9a-fA-F]{7}|-latest)?$"; }
cname_running() { docker ps    --format '{{.Names}}' | grep -E -m1 "$(name_re "$1")" || true; }
cname_any()     { docker ps -a --format '{{.Names}}' | grep -E -m1 "$(name_re "$1")" || true; }
running_color() {
  if   [ -n "$(cname_running blue)"  ]; then echo blue
  elif [ -n "$(cname_running green)" ]; then echo green
  fi
}
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

case "$TARGET" in
  previous)
    STAGE="현재/직전 색 판별"
    CUR="$(running_color)"
    if [ -z "$CUR" ]; then
      echo "::error:: 실행 중인 색이 없다 — previous 롤백 불가 (specific-sha 사용)"
      notify "❌ **롤백 실패** — 실행 중인 색이 없어 previous 불가 → specific-sha 로 롤백하라${LOG_HINT}"
      NOTIFIED=1; exit 1
    fi
    CUR_NAME="$(cname_running "$CUR")"
    PREV=$([ "$CUR" = blue ] && echo green || echo blue)
    PREV_NAME="$(cname_any "$PREV")"
    if [ -z "$PREV_NAME" ]; then
      echo "::error:: 직전 색(app-$PREV) 컨테이너가 없다 — specific-sha 로 롤백하라"
      notify "❌ **롤백 실패** — 직전 색(app-${PREV}) 컨테이너 없음 → specific-sha 로 롤백하라${LOG_HINT}"
      NOTIFIED=1; exit 1
    fi
    PREV_PORT="$(color_port "$PREV")"
    echo ">> previous 롤백: $CUR_NAME → $PREV_NAME (정지 컨테이너 재기동, 이미지 그대로)"
    notify "🔁 **롤백 시작(previous)** — \`${CUR_NAME}\` → \`${PREV_NAME}\` (정지 컨테이너 재기동)${LOG_HINT}"

    STAGE="직전 색 재기동·헬스"
    docker start "$PREV_NAME"
    if ! health_gate "$PREV_PORT"; then
      echo "::error:: $PREV_NAME 헬스체크 실패 — 롤백 중단($CUR_NAME 유지)"
      docker logs --tail 100 "$PREV_NAME" || true
      REASON="$(docker logs --tail 200 "$PREV_NAME" 2>&1 | grep -iE 'error|exception|caused by' | tail -n 5 || true)"
      [ -n "$REASON" ] || REASON="$(docker logs --tail 5 "$PREV_NAME" 2>&1 || true)"
      REASON="$(printf '%s' "$REASON" | tr -d '\000-\010\013\014\016-\037' | head -c 600)"
      # head -c 는 바이트 절단 — UTF-8(한글) 문자 중간이 잘리면 JSON 전체가 invalid → 깨진 꼬리 제거
      RC="$(printf '%s' "$REASON" | iconv -f UTF-8 -t UTF-8 -c 2>/dev/null || true)"; [ -n "$RC" ] && REASON="$RC"
      docker stop "$PREV_NAME" >/dev/null 2>&1 || true
      notify "❌ **롤백 실패 — 헬스체크 타임아웃(120초)** \`${PREV_NAME}\`
전환 없이 중단 → \`${CUR_NAME}\` 이 계속 서빙 중
사유(로그 발췌):
\`\`\`
${REASON:-로그 없음}
\`\`\`${LOG_HINT}"
      NOTIFIED=1; exit 1
    fi
    notify "💚 **헬스체크 통과** — \`${PREV_NAME}\` UP → 트래픽 전환"

    STAGE="트래픽 전환·현재 색 정지"
    echo "reverse_proxy app-$PREV:8080" > caddy/upstream.caddy
    docker exec -w /etc/caddy ytcreator-caddy caddy reload --config /etc/caddy/Caddyfile
    sleep 5
    docker stop "$CUR_NAME" >/dev/null 2>&1 || true
    notify "🌙 **문제 버전 종료** — \`${CUR_NAME}\` 그레이스풀 셧다운"
    echo ">> 롤백 완료: $PREV_NAME 활성"
    notify "✅ **롤백 완료** — 현재 서빙: \`${PREV_NAME}\`"
    NOTIFIED=1
    ;;

  specific-sha)
    STAGE="인자 검증"
    [[ "$SHA" =~ ^[0-9a-fA-F]{7,40}$ ]] || { echo "::error:: 유효하지 않은 SHA: '$SHA'"; exit 1; }
    echo ">> specific-sha 롤백 → deploy.sh 재배포 (IMAGE_TAG=$SHA, .env 참조)"
    chmod +x deploy.sh
    NOTIFIED=1   # 이후 알림(시작/헬스/결과)은 deploy.sh 가 DEPLOY_KIND 로 전담 — 중복 방지
    DEPLOY_KIND="롤백(specific-sha)" bash deploy.sh
    ;;

  *)
    echo "::error:: 알 수 없는 target: '$TARGET' (previous | specific-sha)"; exit 1 ;;
esac
