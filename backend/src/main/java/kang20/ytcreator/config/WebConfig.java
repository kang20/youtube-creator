package kang20.ytcreator.config;

import java.util.List;
import kang20.ytcreator.auth.CurrentUserArgumentResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final List<String> allowedOrigins;
	private final CurrentUserArgumentResolver currentUserArgumentResolver;

	public WebConfig(@Value("${ytcreator.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins,
			CurrentUserArgumentResolver currentUserArgumentResolver) {
		this.allowedOrigins = allowedOrigins;
		this.currentUserArgumentResolver = currentUserArgumentResolver;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
			.allowedOriginPatterns(allowedOrigins.toArray(String[]::new))
			.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
			.allowedHeaders("*")
			.maxAge(3600);
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(currentUserArgumentResolver);
	}
}
