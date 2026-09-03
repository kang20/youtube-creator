package kang20.ytcreator.payment.internal.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import kang20.ytcreator.payment.dto.ProductType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductCatalogTest {

	@Test
	@DisplayName("설정한 상품 코드는 유형으로 판별된다")
	void 판별() {
		ProductCatalog catalog = catalog("one.time", "subscription.monthly");

		assertThat(catalog.typeOf("one.time")).contains(ProductType.CONSUMABLE);
		assertThat(catalog.typeOf("subscription.monthly")).contains(ProductType.SUBSCRIPTION);
	}

	@Test
	@DisplayName("카탈로그에 없는 상품 코드는 판별되지 않는다 — 남의 상품이다")
	void 남의_상품() {
		assertThat(catalog("one.time", "subscription.monthly").typeOf("someone.else")).isEmpty();
	}

	@ParameterizedTest(name = "sku=[{0}]")
	@ValueSource(strings = {"", "   "})
	@DisplayName("상품 코드를 비우면 미노출 상품이다 — 빈 sku 주문도 판별되지 않는다")
	void 미노출_상품(String blank) {
		ProductCatalog catalog = catalog(blank, blank);

		assertThat(catalog.oneTimeSku()).isNull();
		assertThat(catalog.subscriptionSku()).isNull();
		assertThat(catalog.typeOf(blank)).isEmpty();
	}

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
