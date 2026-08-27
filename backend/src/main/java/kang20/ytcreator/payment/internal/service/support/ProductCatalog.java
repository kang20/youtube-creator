package kang20.ytcreator.payment.internal.service.support;

import java.util.Optional;
import kang20.ytcreator.payment.dto.ProductType;
import kang20.ytcreator.shared.support.Support;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 상품 카탈로그 — 상품 코드로 상품 유형을 판별한다(new-domain/payment.md 주문 애그리거트).
 *
 * <p>🔴 <b>판별 대상은 토스가 답한 상품 코드다.</b> 클라이언트가 주장한 값이 아니다.
 * 카탈로그에 없으면 <b>남의 상품</b>이므로 지급하지 않는다.
 *
 * <p><b>상품 코드를 코드 상수로 박지 않는다</b> — 설정으로 뺀다. 키:
 * {@code ytcreator.payment.one-time.sku} · {@code ytcreator.payment.subscription.sku}.
 * 값을 비우면 미노출 상품이 되어 어떤 주문도 그 유형으로 판별되지 않는다.
 *
 * <p>⚠️ <b>가격을 담지 않는다</b> — 가격의 정본은 결제 SDK 이고, 우리가 말하면 결제창과 어긋난다.
 */
@ConfigurationProperties("ytcreator.payment")
@Support
public class ProductCatalog {

	private final Product oneTime = new Product();

	private final Product subscription = new Product();

	public Product getOneTime() {
		return oneTime;
	}

	public Product getSubscription() {
		return subscription;
	}

	/** 단건 상품 코드 — 미설정이면 null(미노출). */
	public String oneTimeSku() {
		return normalize(oneTime.getSku());
	}

	/** 구독 상품 코드 — 미설정이면 null(미노출). */
	public String subscriptionSku() {
		return normalize(subscription.getSku());
	}

	/** 상품 코드 → 상품 유형. 카탈로그에 없으면 empty — 남의 상품이다. */
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
