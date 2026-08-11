/**
 * payment 가 노출하는 dto 인터페이스(payment-design.md §4 — {@code payment/dto/} public) —
 * Spring Modulith 는 모듈 루트의 public 타입만 API 로 보므로, 서브패키지 노출은 이 선언이 근거다.
 * 참조하는 모듈(bootstrap 등)은 {@code allowedDependencies} 에 {@code "payment :: dto"} 를 명시해야 한다.
 */
@NamedInterface("dto")
package kang20.ytcreator.payment.dto;

import org.springframework.modulith.NamedInterface;
