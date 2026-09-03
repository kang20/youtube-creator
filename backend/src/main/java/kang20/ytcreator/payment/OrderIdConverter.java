package kang20.ytcreator.payment;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class OrderIdConverter implements AttributeConverter<OrderId, String> {

	@Override
	public String convertToDatabaseColumn(OrderId attribute) {
		return attribute == null ? null : attribute.raw();
	}

	@Override
	public OrderId convertToEntityAttribute(String dbData) {
		return dbData == null ? null : new OrderId(dbData);
	}
}
