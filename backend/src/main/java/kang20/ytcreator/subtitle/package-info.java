/**
 * 자막 — 돈을 낸 사용자가 어디서 이탈해도 결과물을 잃지 않게 한다(new-domain/subtitle/subtitle-v3.md).
 *
 * <p><b>애그리거트는 작업(Job) 하나다.</b> 대본·자막 파일의 실물은 객체 스토리지에 있고
 * 이 모듈이 들고 있는 것은 위치({@code StorageKey})뿐이다 — 세 위치 모두 작업 번호로 정해진다.
 *
 * <p><b>공개 표면은 비어 있다</b> — 포트를 부르는 것은 자기 컨트롤러·스케줄러·리스너·큐 소비자뿐이라 전부
 * {@code internal/port} 에 있다(architecture.md R1).
 *
 * <p>워커와는 Redis Stream 둘(작업 큐·완료 큐)로만 닿는다. 서버→큐는 {@code WorkRequested} → 아웃박스
 * (event_publication) → {@code WorkDispatcher} 한 길이고, 큐→서버는 {@code WorkCompletionConsumer} 가
 * {@code SubtitleWorkerPort} 를 부르는 한 길이다. 큐 설정({@code ytcreator.subtitle.queue.enabled})이 꺼지면
 * 의뢰는 거부되고 완료 큐는 읽지 않는다.
 *
 * <p>이용권은 payment 의 {@code PaymentUsagePort} 하나로만 부른다 — 잔량도 구독 상태도 직접
 * 보지 않고 통과 여부만 안다. {@code auth} 에서 쓰는 것은 {@code UserId} 하나다.
 */
@ApplicationModule(displayName = "자막", allowedDependencies = {"shared", "auth", "payment"})
package kang20.ytcreator.subtitle;

import org.springframework.modulith.ApplicationModule;
