package kang20.ytcreator.payment;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.dto.EntitlementView;
import kang20.ytcreator.payment.dto.ProductCatalog;

/**
 * 결제·이용권 <b>조회 포트</b> — 읽기 전용 질의(payment-design.md §4).
 * 소비자: bootstrap(진입 응답 조립) · payment 자신의 컨트롤러(상품·이용권 조회).
 *
 * <p>모듈 루트의 {@code *Port} 인터페이스가 모듈 공개 계약의 전부다 — 구현체
 * {@code internal.service.PaymentService} 는 직접 참조할 수 없다(컨트롤러도 포트로만 부른다).
 * 사용자 단위 메서드는 {@link UserId} 를 받는다 — <b>익명키를 받지 않는다</b>(§2-1 쟁점 1).
 */
public interface PaymentReaderPort {

	/**
	 * U1(§5-1) — 상품 카탈로그. 설정에서 읽고 DB 를 보지 않는다(가격은 SDK displayAmount 가 정본).
	 * {@code /products} 는 공개 경로라 인증 없이 호출된다.
	 */
	ProductCatalog products();

	/**
	 * U5 이용권 조회(§5-3) — 읽기 전용. <b>토스도 auth 도 부르지 않는다.</b>
	 * 부트스트랩이 부분 실패를 허용하지 않으므로 여기서 외부를 부르면 토스 장애가 진입을 막는다.
	 * 이용권이 없어도 404 가 아니라 200 — "없음"은 정상 상태다.
	 */
	EntitlementView entitlementOf(UserId userId);
}
