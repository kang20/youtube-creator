package kang20.ytcreator.payment.internal.support;

import static org.assertj.core.api.Assertions.assertThat;

import kang20.ytcreator.payment.dto.ProductType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상품 카탈로그 설정 바인딩 단위 — sku → 상품 유형 판별(payment-design.md §5-2③)과
 * 미설정 = 미노출(payment.md §5-2) 규칙.
 */
class ProductCatalogPropertiesTest {

	private static ProductCatalogProperties catalog(String oneTimeSku, String subscriptionSku) {
		ProductCatalogProperties properties = new ProductCatalogProperties();
		properties.getOneTime().setSku(oneTimeSku);
		properties.getSubscription().setSku(subscriptionSku);
		return properties;
	}

	/** §5-2③ — 토스 응답 sku 로 상품을 판별한다. 클라 주장이 아니다 */
	@Test
	@DisplayName("sku 로 상품 유형을 판별한다")
	void 유형_판별() {
		ProductCatalogProperties catalog = catalog("sku-a", "sku-b");

		assertThat(catalog.typeOf("sku-a")).contains(ProductType.CONSUMABLE);
		assertThat(catalog.typeOf("sku-b")).contains(ProductType.SUBSCRIPTION);
	}

	/** §5-2③ — 카탈로그에 없는 sku 는 남의 상품(empty → PAY_004 경로) */
	@Test
	@DisplayName("카탈로그에 없는 sku 와 null sku 는 empty 다")
	void 남의_상품과_null() {
		ProductCatalogProperties catalog = catalog("sku-a", "sku-b");

		assertThat(catalog.typeOf("sku-else")).isEmpty();
		// 토스가 NOT_FOUND 류에서 sku 를 안 주는 경우의 방어(§5-6)
		assertThat(catalog.typeOf(null)).isEmpty();
	}

	/** payment.md §5-2 — 미설정(빈 값·공백)은 미노출 상품이다(null). 프론트가 버튼을 숨긴다 */
	@Test
	@DisplayName("미설정·공백 sku 는 null(미노출)로 정규화된다")
	void 미설정은_미노출() {
		ProductCatalogProperties empty = catalog(null, "   ");

		assertThat(empty.oneTimeSku()).isNull();
		assertThat(empty.subscriptionSku()).isNull();
		assertThat(empty.typeOf("anything")).isEmpty();
	}

	/** 미노출 상품과 sku 가 우연히 매칭되면 안 된다 — null 카탈로그에 null 비교 함정 방어 */
	@Test
	@DisplayName("한쪽만 노출된 카탈로그에서 다른 쪽 유형이 나오지 않는다")
	void 부분_노출() {
		ProductCatalogProperties oneTimeOnly = catalog("sku-a", null);

		assertThat(oneTimeOnly.typeOf("sku-a")).contains(ProductType.CONSUMABLE);
		assertThat(oneTimeOnly.subscriptionSku()).isNull();
	}
}
