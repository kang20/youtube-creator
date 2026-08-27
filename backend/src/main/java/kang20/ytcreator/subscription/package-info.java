/**
 * 구독·기간권 — 매월 자동 갱신되는 <b>계약</b>과 그 계약이 만들어내는 기간권에 권위를 갖는다
 * (new-domain/payment.md 구독 애그리거트).
 *
 * <p>🔴 <b>이 도메인 최대의 구조적 제약: 서버가 구독 상태를 물어볼 API 가 없다.</b> 최초 결제 이후의
 * 상태 변화를 아는 경로는 웹훅 하나뿐이고, 어긋나도 바로잡을 조회 수단이 없다. 그래서 우리가 아는
 * 구독 상태는 "최초 주문 검증 + 이후 웹훅 누적"이 전부다.
 *
 * <p>쓰기 진입은 셋이고 소비자 축이 둘이다:
 * <ul>
 *   <li>ⓐ <b>이벤트 리스너</b> — payment 가 발행한 {@code SubscriptionGranted} 를 받아
 *       {@code internal.port.SubscriptionGrantPort} 로 계약 한 행을 연다. <b>구독은 검증된 주문을
 *       거쳐서만 생긴다</b> — 이 규칙이 위조 웹훅의 최대 피해를 "없는 구독 생성"에서 "이미 결제한
 *       사람의 상태 흔들기"로 줄인다.</li>
 *   <li>ⓑ <b>HTTP inbound</b> — 웹훅 수신(정본 반영)과 재확인(임시 보정)이
 *       {@code internal.port.SubscriptionStatusPort} 하나로 들어온다.</li>
 * </ul>
 *
 * <p><b>이 모듈의 공개 표면은 비어 있다</b> — 두 포트 모두 자기 리스너·컨트롤러만 부르므로
 * {@code internal/port} 에 있다(architecture.md "공개 표면", R1).
 *
 * <p>⚠️ <b>이용권 읽기 모델(Entitlement)·이용 티켓은 아직 없다</b> — 횟수권(credit)과 합성하는
 * 조회 경로와 bootstrap 재연결은 다음 범위다. 소비자 없는 포트·DTO 는 만들지 않는다
 * (architecture.md 포트 선례). 그래서 {@code SubscriptionStatus} 도 아직 {@code internal} 에 있다 —
 * 밖으로 내보낼 소비자가 생기면 그때 모듈 루트로 올린다.
 *
 * <p>{@code payment} 의존은 두 가지다 — 이벤트 record({@code SubscriptionGranted}) 참조와,
 * {@code order_id} 를 저장하기 위한 타입 ID·영속화 어댑터({@code OrderId}·{@code OrderIdConverter}).
 * 둘 다 행위 호출이 아니라 <b>데이터</b>다. {@code auth} 에서 쓰는 것은 {@code UserId} 하나다.
 */
@ApplicationModule(displayName = "구독·기간권", allowedDependencies = {"shared", "auth", "payment"})
package kang20.ytcreator.subscription;

import org.springframework.modulith.ApplicationModule;
