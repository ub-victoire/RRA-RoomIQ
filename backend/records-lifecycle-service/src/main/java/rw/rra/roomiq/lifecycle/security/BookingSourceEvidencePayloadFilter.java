package rw.rra.roomiq.lifecycle.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Component
public class BookingSourceEvidencePayloadFilter extends OncePerRequestFilter {
    private static final String INGESTION_PATH = "/internal/v1/source-evidence/booking-requests";
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!"POST".equals(request.getMethod()) || !INGESTION_PATH.equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!isJson(request.getContentType())) {
            response.sendError(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
            return;
        }
        if (request.getContentLengthLong() > MAX_PAYLOAD_BYTES) {
            response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value());
            return;
        }
        byte[] payload = request.getInputStream().readNBytes(MAX_PAYLOAD_BYTES + 1);
        if (payload.length > MAX_PAYLOAD_BYTES) {
            response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value());
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, payload), response);
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            return MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] payload;

        private CachedBodyRequest(HttpServletRequest request, byte[] payload) {
            super(request);
            this.payload = payload;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(payload);
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
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
