package com.codepulse.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import com.codepulse.auth.UserPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

public class SubmissionRequestGuardFilter extends OncePerRequestFilter {

    private static final int MAX_SOURCE_BYTES = 64 * 1024;
    private static final int MAX_JSON_BODY_BYTES = MAX_SOURCE_BYTES * 6 + 4_096;

    private final SubmissionRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public SubmissionRequestGuardFilter(SubmissionRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && "/api/submissions".equals(request.getServletPath()));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            jakarta.servlet.FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal
                && !rateLimiter.tryAcquire(principal.id())) {
            writeError(response, HttpStatus.TOO_MANY_REQUESTS.value(), "Submission rate limit exceeded");
            return;
        }

        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_JSON_BODY_BYTES) {
            writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Submission request is too large");
            return;
        }

        byte[] body = request.getInputStream().readNBytes(MAX_JSON_BODY_BYTES + 1);
        if (body.length > MAX_JSON_BODY_BYTES) {
            writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Submission request is too large");
            return;
        }
        if (body.length > 0) {
            try {
                JsonNode requestJson = objectMapper.readTree(body);
                JsonNode sourceCode = requestJson == null ? null : requestJson.get("sourceCode");
                if (sourceCode != null
                        && sourceCode.isTextual()
                        && sourceCode.asText().getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
                    writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Source code is limited to 64 KiB");
                    return;
                }
            } catch (JsonProcessingException exception) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Malformed JSON submission");
                return;
            }
        }

        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new IllegalStateException("Asynchronous request reads are not supported");
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
