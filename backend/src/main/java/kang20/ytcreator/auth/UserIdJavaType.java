package kang20.ytcreator.auth;

import kang20.ytcreator.shared.domain.LongTypeIdentifierJavaType;

/**
 * {@link UserId} 의 Hibernate 매핑 어댑터 — payment 등 참조 모듈의 엔티티가
 * {@code @JavaType(UserIdJavaType.class)} 값 컬럼({@code user_id BIGINT})으로 쓴다
 * (payment-design.md §3 · architecture.md "타입화된 기본키").
 */
public class UserIdJavaType extends LongTypeIdentifierJavaType<UserId> {

	public UserIdJavaType() {
		super(UserId.class);
	}
}
