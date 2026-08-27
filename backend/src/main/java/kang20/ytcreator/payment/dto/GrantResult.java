package kang20.ytcreator.payment.dto;

/**
 * 지급 결과.
 *
 * <p>🔴 <b>신규 지급과 재요청의 응답 모양이 같다.</b> 중복 요청은 오류가 아니라 정상이고
 * (복원·재시도·타임아웃 후 실은 성공), 재요청도 성공으로 답해야 클라이언트가 미결을 닫는다.
 * 재요청을 오류로 만들면 복원 흐름 자체가 깨진다.
 *
 * <p>⚠️ <b>주문 식별자를 싣지 않는다</b> — 아는 것 자체가 미지급 주문을 가로챌 수단이 된다.
 *
 * <p>⚠️ <b>이용권 상태가 빠져 있다</b>(2026-08-14 재설계 진행 중) — 횟수권 잔량·기간권
 * 애그리거트가 아직 없다. 붙으면 이 record 에 이용권 필드가 들어오고,
 * 재요청의 잔량은 <b>현재 잔량</b>이어야 한다(다시 오르면 멱등이 깨진 것이다).
 * 잔량은 credit 이 알고 payment 는 읽을 수단이 없다(모듈 경계) — 잔량이 필요한 화면은
 * credit 의 조회 경로로 가야 한다.
 *
 * @param granted     지급됐으면 true. 재요청도 true 다
 * @param productType 무엇을 지급했는지 — 화면 분기용
 */
public record GrantResult(boolean granted, ProductType productType) {
}
