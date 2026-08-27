package kang20.ytcreator.payment;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@link OrderId} 의 JPA 매핑 어댑터 — {@code VARCHAR} 값 컬럼으로 오간다.
 *
 * <p>{@code UserId} 계열이 쓰는 {@code @JavaType} 대신 {@code AttributeConverter} 를 쓰는 이유:
 * 저 계열은 {@code Long} 기본키 타입화라 공통 부모({@code LongTypeIdentifier})가 있지만,
 * 주문 식별자는 <b>문자열 자연키</b>라 그 계보에 들어가지 않는다.
 *
 * <p>🔴 <b>모듈 루트에 있다 = 공개 어댑터다</b>(2026-08-14 이동). architecture.md 의 판정 기준은
 * "다른 모듈이 그 값을 저장하는가" 하나다 — {@code subscription} 모듈이 <b>웹훅이 구독을 찾아오는
 * 유일한 키</b>로 {@code order_id} 를 자기 테이블에 저장하게 되면서, 어댑터를 {@code internal} 에
 * 숨겨 둘 근거가 사라졌다({@code UserIdJavaType} 이 공개인 것과 같은 취급이다).
 * 저장하는 모듈이 다시 없어지면 되돌린다.
 *
 * <p>⚠️ 어댑터를 공개해도 <b>원문 비노출 정책은 그대로다</b> — 어댑터가 여는 것은 "다른 모듈이
 * 이 값을 컬럼으로 매핑할 수 있다"뿐이고, 응답·로그·예외 메시지 금지는 {@link OrderId#toString()}
 * 이 계속 강제한다.
 *
 * <p>⚠️ {@code autoApply} 를 켜지 않는다 — 필드마다 {@code @Convert} 로 명시한다.
 * 전역 적용은 나중에 다른 문자열 VO 가 생겼을 때 조용히 잘못 걸린다.
 */
@Converter
public class OrderIdConverter implements AttributeConverter<OrderId, String> {

	@Override
	public String convertToDatabaseColumn(OrderId attribute) {
		return attribute == null ? null : attribute.raw();
	}

	@Override
	public OrderId convertToEntityAttribute(String dbData) {
		return dbData == null ? null : new OrderId(dbData);
	}
}
