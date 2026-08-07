#!/usr/bin/env bash
# OCI Ubuntu VM 최초 1회 실행 스크립트.
# 사용법: bash setup-vm.sh app   (앱 VM — 80/443 오픈)
#         bash setup-vm.sh db    (DB VM — 3306 을 앱 서브넷에만 오픈)
set -euo pipefail

ROLE="${1:?사용법: bash setup-vm.sh <app|db>}"

echo "==> 1/4 패키지 업데이트"
sudo apt-get update -y

echo "==> 2/4 Docker 설치"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker "$USER"
else
  echo "    Docker 이미 설치됨 — 건너뜀"
fi

echo "==> 3/4 방화벽(iptables) 오픈 — OCI Ubuntu 이미지는 기본 REJECT 규칙이 있음"
# ⚠️ 반드시 REJECT 규칙 "앞"에 삽입해야 한다 — 고정 위치(6) 가정은 이미지에 따라 REJECT 뒤에
# 꽂혀 죽은 규칙이 된다(실제 발생). REJECT 줄 번호를 찾아 그 위치에 삽입한다.
# (참고: docker ports 매핑 컨테이너는 FORWARD 체인이라 INPUT 규칙과 무관 — host 네트워크만 INPUT 통과)
insert_before_reject() {
  local pos
  pos=$(sudo iptables -L INPUT --line-numbers | awk '/REJECT/{print $1; exit}')
  pos=${pos:-6}
  sudo iptables -I INPUT "$pos" "$@"
}
if [ "$ROLE" = "app" ]; then
  insert_before_reject -m state --state NEW -p tcp --dport 80 -j ACCEPT
  insert_before_reject -m state --state NEW -p tcp --dport 443 -j ACCEPT
else
  insert_before_reject -m state --state NEW -p tcp -s 10.0.0.0/24 --dport 3306 -j ACCEPT
  # mysqld_exporter 메트릭 (Prometheus 수집, 앱 서브넷 한정)
  insert_before_reject -m state --state NEW -p tcp -s 10.0.0.0/24 --dport 9104 -j ACCEPT
  # node_exporter 호스트 메트릭 (host 네트워크 = INPUT 체인 통과, 앱 서브넷 한정)
  insert_before_reject -m state --state NEW -p tcp -s 10.0.0.0/24 --dport 9100 -j ACCEPT
fi
sudo apt-get install -y netfilter-persistent iptables-persistent >/dev/null 2>&1 || true
sudo netfilter-persistent save || echo "    (netfilter-persistent 저장 실패 — 재부팅 시 규칙이 사라질 수 있으니 확인 필요)"

echo "==> 4/4 배포 디렉토리 생성"
mkdir -p "$HOME/ytcreator"

echo ""
echo "✅ [$ROLE] 완료. 다음 순서:"
echo "   1) 로그아웃 후 재접속 (docker 그룹 반영)"
echo "   2) GitHub Actions 배포 실행 (.env 는 배포 시 GitHub 시크릿에서 자동 생성됨)"
