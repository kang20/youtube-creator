package kang20.ytcreator.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS — 허용 오리진은 환경변수로 주입한다(프론트 배포처가 정해지면 값만 바꾼다).
 * 기본값은 로컬 개발 서버뿐이라 운영에서 값을 안 주면 브라우저 호출이 막힌다(의도된 fail-closed).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final List<String> allowedOrigins;

	public WebConfig(@Value("${ytcreator.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
		this.allowedOrigins = allowedOrigins;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
			.allowedOriginPatterns(allowedOrigins.toArray(String[]::new))
			.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
			.allowedHeaders("*")
			.maxAge(3600);
	}
}
