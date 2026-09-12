package com.example.payment.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesRequestIdWhenHeaderMissingOrBlank() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "  ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String id = (String) request.getAttribute(RequestIdFilter.ATTR);
        assertThat(id).startsWith("req_");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(id);
        verify(chain).doFilter(request, response);
    }

    @Test
    void preservesIncomingRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "req_client");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("req_client");
        assertThat(RequestIdFilter.resolve(request)).isEqualTo("req_client");
    }

    @Test
    void resolveFallsBackToHeaderThenUnknown() {
        MockHttpServletRequest withHeader = new MockHttpServletRequest();
        withHeader.addHeader(RequestIdFilter.HEADER, "req_hdr");
        assertThat(RequestIdFilter.resolve(withHeader)).isEqualTo("req_hdr");

        MockHttpServletRequest blankAttr = new MockHttpServletRequest();
        blankAttr.setAttribute(RequestIdFilter.ATTR, "  ");
        blankAttr.addHeader(RequestIdFilter.HEADER, "req_from_header");
        assertThat(RequestIdFilter.resolve(blankAttr)).isEqualTo("req_from_header");

        MockHttpServletRequest empty = new MockHttpServletRequest();
        empty.setAttribute(RequestIdFilter.ATTR, 123);
        assertThat(RequestIdFilter.resolve(empty)).isEqualTo("req_unknown");
    }
}
