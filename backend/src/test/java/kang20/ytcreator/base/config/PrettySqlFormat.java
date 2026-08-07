package kang20.ytcreator.base.config;

import com.p6spy.engine.logging.Category;
import com.p6spy.engine.spy.appender.MessageFormattingStrategy;
import org.hibernate.engine.jdbc.internal.FormatStyle;

/**
 * p6spy 가 찍는 SQL 을 Hibernate 포매터로 정리해 읽을 수 있게 만든다. 테스트 전용.
 */
public class PrettySqlFormat implements MessageFormattingStrategy {

	@Override
	public String formatMessage(int connectionId, String now, long elapsed, String category,
			String prepared, String sql, String url) {
		return sql.isBlank() ? "" : format(category, sql) + " {elapsed: " + elapsed + "ms}";
	}

	private String format(String category, String sql) {
		if (Category.STATEMENT.getName().equals(category)) {
			String trimmed = sql.trim().toLowerCase();
			if (trimmed.startsWith("create") || trimmed.startsWith("alter") || trimmed.startsWith("comment")) {
				return FormatStyle.DDL.getFormatter().format(sql);
			}
			return FormatStyle.BASIC.getFormatter().format(sql);
		}
		return sql;
	}
}
