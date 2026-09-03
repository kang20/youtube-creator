package kang20.ytcreator.payment.internal.entity.dto;

import kang20.ytcreator.payment.dto.ProductType;
import kang20.ytcreator.payment.OrderId;

public record GrantRequest(OrderId orderId, String sku, ProductType productType) {
}
