package com.example.chatgateway.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class LocalApiDocsConfigurationTest {
  private final WebApplicationContextRunner context =
      new WebApplicationContextRunner()
          .withUserConfiguration(Mvc.class, LocalApiDocsConfiguration.class);

  @Configuration
  @EnableWebMvc
  static class Mvc {}

  @Test
  void localServesSpecUiAssetsAndPdf() {
    context
        .withPropertyValues("spring.profiles.active=local")
        .run(
            ctx -> {
              var mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
              mvc.perform(get("/docs/index.html"))
                  .andExpect(status().isOk())
                  .andExpect(content().string(containsString("swagger-ui")));
              mvc.perform(get("/docs/external-api.yaml"))
                  .andExpect(status().isOk())
                  .andExpect(content().string(containsString("/api/click:")));
              mvc.perform(get("/docs/python-internal-api.yaml")).andExpect(status().isOk());
              mvc.perform(get("/docs/vendor/swagger-ui-bundle.js")).andExpect(status().isOk());
              mvc.perform(get("/docs/gateway-api-guide.pdf")).andExpect(status().isOk());
            });
  }

  @Test
  void nonLocalDoesNotExposeDocumentation() {
    context.run(
        ctx -> {
          var mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
          for (String path :
              new String[] {
                "index.html",
                "external-api.yaml",
                "python-internal-api.yaml",
                "gateway-api-guide.pdf",
                "vendor/swagger-ui-bundle.js"
              }) {
            mvc.perform(get("/docs/" + path)).andExpect(status().isNotFound());
          }
        });
  }
}
