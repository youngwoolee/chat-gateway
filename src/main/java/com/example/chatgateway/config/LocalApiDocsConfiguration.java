package com.example.chatgateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** local에서만 정적 OpenAPI·Swagger UI·배포용 PDF를 제공한다. */
@Configuration
@Profile("local")
public class LocalApiDocsConfiguration implements WebMvcConfigurer {
  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/docs/**").addResourceLocations("classpath:/api-docs/");
    registry
        .addResourceHandler("/docs/vendor/**")
        .addResourceLocations("classpath:/META-INF/resources/webjars/swagger-ui/5.33.2/");
  }
}
