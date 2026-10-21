package com.example.chatgateway.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class SameOriginFilterTest {
  private final SameOriginFilter filter = new SameOriginFilter();

  @Test
  void acceptsMatchingOrigin() throws Exception {
    var exchange =
        (MockMvcRequestBuilders.post("https://chat.example/api/auth/session")
            .header("Origin", "https://chat.example")
            .header("Sec-Fetch-Site", "same-origin"));
    FilterChain chain = mock(FilterChain.class);
    var response = new MockHttpServletResponse();
    var request = exchange.buildRequest(new MockServletContext());
    request.setServerPort(443);
    filter.doFilter(request, response, chain);
    verify(chain).doFilter(any(), any());
  }

  @Test
  void rejectsCrossSiteOrigin() throws Exception {
    var exchange =
        (MockMvcRequestBuilders.post("https://chat.example/api/auth/session")
            .header("Origin", "https://evil.example"));
    FilterChain chain = mock(FilterChain.class);
    var response = new MockHttpServletResponse();
    var request = exchange.buildRequest(new MockServletContext());
    request.setServerPort(443);
    filter.doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(403);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void rejectsCrossSiteFetchMetadataEvenWithoutOrigin() throws Exception {
    var exchange =
        (MockMvcRequestBuilders.post("https://chat.example/api/auth/session")
            .header("Sec-Fetch-Site", "cross-site"));
    FilterChain chain = mock(FilterChain.class);
    var response = new MockHttpServletResponse();
    var request = exchange.buildRequest(new MockServletContext());
    request.setServerPort(443);
    filter.doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(403);
    verify(chain, never()).doFilter(any(), any());
  }
}
