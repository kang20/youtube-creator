package kang20.ytcreator.payment.internal.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import kang20.ytcreator.payment.dto.ProductType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 상품 카탈로그의 판별 규칙 (new-domain/payment.md 주문 애그리거트 · {@link ProductCatalog} javadoc).
 *
 * <p>설정 바인딩 결과물이므로 스프링을 띄우지 않고 값만 넣어 본다 — 바인딩 자체는 지급 흐름 테스트
 * ({@code PaymentServiceTest})가 실제 프로퍼티로 간접 검증한다.
 *
 * <p>여기서 보는 것은 <b>미노출(값을 비움)</b> 규칙이다: 상품 코드를 비우면 어떤 주문도 그 유형으로
 * 판별되지 않아야 한다. 이게 깨지면 빈 문자열 sku 를 답한 주문이 우리 상품으로 지급된다.
 */
class ProductCatalogTest {

	@Test
	@DisplayName("설정한 상품 코드는 유형으로 판별된다")
	void 판별() {
		ProductCatalog catalog = catalog("one.time", "subscription.monthly");

		assertThat(catalog.typeOf("one.time")).contains(ProductType.CONSUMABLE);
		assertThat(catalog.typeOf("subscription.monthly")).contains(ProductType.SUBSCRIPTION);
	}

	/** 🔴 카탈로그에 없으면 남의 상품이다 — 결제됐다고 해서 우리가 지급하지 않는다. */
	@Test
	@DisplayName("카탈로그에 없는 상품 코드는 판별되지 않는다 — 남의 상품이다")
	void 남의_상품() {
		assertThat(catalog("one.time", "subscription.monthly").typeOf("someone.else")).isEmpty();
	}

	/**
	 * 상품 코드를 비우면 <b>미노출 상품</b>이다. 빈 값이 "빈 sku 를 답한 주문"과 같은 값으로 취급돼
	 * 지급되면 안 되므로, 정규화가 빈 값을 null 로 접는지 본다.
	 */
	@ParameterizedTest(name = "sku=[{0}]")
	@ValueSource(strings = {"", "   "})
	@DisplayName("상품 코드를 비우면 미노출 상품이다 — 빈 sku 주문도 판별되지 않는다")
	void 미노출_상품(String blank) {
		ProductCatalog catalog = catalog(blank, blank);

		assertThat(catalog.oneTimeSku()).isNull();
		assertThat(catalog.subscriptionSku()).isNull();
		assertThat(catalog.typeOf(blank)).isEmpty();
	}

	/** 설정 자체가 없는 경우(값 미설정)도 미노출이다. */
	@Test
	@DisplayName("설정이 아예 없으면 어떤 상품 코드도 판별되지 않는다")
	void 미설정() {
		ProductCatalog catalog = new ProductCatalog();

		assertThat(catalog.oneTimeSku()).isNull();
		assertThat(catalog.subscriptionSku()).isNull();
		assertThat(catalog.typeOf("one.time")).isEmpty();
		assertThat(catalog.typeOf(null)).isEmpty();
	}

	private static ProductCatalog catalog(String oneTimeSku, String subscriptionSku) {
		ProductCatalog catalog = new ProductCatalog();
		catalog.getOneTime().setSku(oneTimeSku);
		catalog.getSubscription().setSku(subscriptionSku);
		return catalog;
	}
}
