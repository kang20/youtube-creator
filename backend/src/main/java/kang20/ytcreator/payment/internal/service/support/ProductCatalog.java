package kang20.ytcreator.payment.internal.service.support;

import java.util.Optional;
import kang20.ytcreator.payment.dto.ProductType;
import kang20.ytcreator.shared.support.Support;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

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

	public String oneTimeSku() {
		return normalize(oneTime.getSku());
	}

	public String subscriptionSku() {
		return normalize(subscription.getSku());
	}

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
