package kang20.ytcreator.payment.internal.support;

import java.util.Optional;
import kang20.ytcreator.payment.dto.ProductType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 상품 {@code sku} 설정 바인딩(payment-design.md §5-1) — <b>코드 상수로 박지 않는다.</b>
 * payment.md §4-11 이 가격 하드코딩을 금지했고 sku 도 같은 축이다.
 *
 * <p>키: {@code ytcreator.payment.one-time.sku} · {@code ytcreator.payment.subscription.sku}.
 * 값을 비우면 미노출 상품 — {@code products} 응답에 null 로 내려가고 프론트가 버튼을 숨긴다.
 */
@Component
@ConfigurationProperties("ytcreator.payment")
public class ProductCatalogProperties {

	private final Product oneTime = new Product();
	private final Product subscription = new Product();

	public Product getOneTime() {
		return oneTime;
	}

	public Product getSubscription() {
		return subscription;
	}

	/** 단건 소모품 sku — 미설정이면 null(미노출). */
	public String oneTimeSku() {
		return normalize(oneTime.getSku());
	}

	/** 월 구독 sku — 미설정이면 null(미노출). */
	public String subscriptionSku() {
		return normalize(subscription.getSku());
	}

	/** 토스 응답 sku → 상품 유형(§5-2③). 카탈로그에 없으면 empty — 남의 상품이다(PAY_004). */
	public Optional<ProductType> typeOf(String sku) {
		if (sku == null) {
			return Optional.empty();
		}
		if (sku.equals(oneTimeSku())) {
			return Optional.of(ProductType.CONSUMABLE);
		}
		if (sku.equals(subscriptionSku())) {
			return Optional.of(ProductType.SUBSCRIPTION);
		}
		return Optional.empty();
	}

	private static String normalize(String sku) {
		return StringUtils.hasText(sku) ? sku : null;
	}

	/** 상품 1종의 설정 홀더 — JavaBean 바인딩 대상. */
	public static class Product {

		private String sku;

		public String getSku() {
			return sku;
		}

		public void setSku(String sku) {
			this.sku = sku;
		}
	}
}
