package kang20.ytcreator.shared.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.stereotype.Component;

/**
 * <b>서비스 지원 부품</b> 표식 — 한 도메인 모듈의 {@code internal/service/support} 에 사는 클래스에만 붙는다
 * (architecture.md "Port·Service·Support 규약").
 *
 * <p>이 표식이 뜻하는 계약은 하나다: <b>이 부품은 같은 모듈의 {@code *Service} 만 참조할 수 있다.</b>
 * 컨트롤러·리포지토리·다른 support 는 support 를 직접 부를 수 없다 — 오케스트레이션의 단일 주인은
 * {@code *Service} 다. 이 계약은 {@code ArchitectureConventionTest} 가 강제한다.
 *
 * <p><b>메타 {@code @Component} 다 — 이것만으로 빈이 된다.</b> support 는 정의상 Service 가 주입받는
 * 협력자이므로 빈이 아닌 support 는 존재할 수 없다. 레이어를 안 가리는 정적 유틸(예: 로그 마스킹)은
 * 애초에 support 가 아니라 {@code internal/} 루트의 평범한 클래스다(architecture.md 규약 §6).
 *
 * <p>⚠️ <b>{@code @Component} 를 함께 붙이지 마라</b> — 중복이다. 다만 설정 바인딩 부품은
 * {@code @ConfigurationProperties} 를 <b>추가로</b> 붙인다(그건 스캔이 아니라 바인딩 표식이다).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface Support {
}
