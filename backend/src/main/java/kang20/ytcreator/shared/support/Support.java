package kang20.ytcreator.shared.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <b>서비스 지원 부품</b> 표식 — 한 도메인 모듈의 {@code internal/service/support} 에 사는 클래스에만 붙는다
 * (architecture.md "Port·Service·Support 규약").
 *
 * <p>이 표식이 뜻하는 계약은 하나다: <b>이 부품은 같은 모듈의 {@code *Service} 만 참조할 수 있다.</b>
 * 컨트롤러·리포지토리·다른 support 는 support 를 직접 부를 수 없다 — 오케스트레이션의 단일 주인은
 * {@code *Service} 다. 이 계약은 {@code ArchitectureConventionTest} 가 강제한다.
 *
 * <p>빈 등록은 하지 않는다(메타 {@code @Component} 아님) — 순수 정적 유틸(예: 마스킹)도 support 로
 * 분류되기 때문이다. 빈이 필요한 support 는 각자 {@code @Component}/{@code @ConfigurationProperties} 를
 * 함께 붙인다.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Support {
}
