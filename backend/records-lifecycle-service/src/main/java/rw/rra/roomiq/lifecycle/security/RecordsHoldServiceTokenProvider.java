package rw.rra.roomiq.lifecycle.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Component
public class RecordsHoldServiceTokenProvider {
    public static final String SUBJECT = "records-lifecycle-service";
    public static final String AUDIENCE = "rra-roomiq-booking-legal-hold";
    public static final String PERMISSION = "BOOKING_SOURCE_HOLD_ENFORCE";
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(2);

    private final ResourceLoader resourceLoader;
    private final String issuer;
    private final String keyId;
    private final String privateKeyLocation;
    private final String bookingUrl;
    private final RestClient bookingClient;
    private final Clock clock;

    @Autowired
    public RecordsHoldServiceTokenProvider(
            ResourceLoader resourceLoader,
            @Value("${roomiq.lifecycle.booking-hold-instructions.url:}") String bookingUrl,
            @Value("${roomiq.lifecycle.booking-hold-instructions.connect-timeout:2s}") Duration connectTimeout,
            @Value("${roomiq.lifecycle.booking-hold-instructions.read-timeout:5s}") Duration readTimeout,
            @Value("${roomiq.auth.records-lifecycle-service-token.issuer:}") String issuer,
            @Value("${roomiq.auth.records-lifecycle-service-token.key-id:}") String keyId,
            @Value("${roomiq.auth.records-lifecycle-service-token.private-key-location:}") String privateKeyLocation) {
        this(resourceLoader, bookingUrl, connectTimeout, readTimeout, issuer, keyId, privateKeyLocation,
                Clock.systemUTC());
    }

    RecordsHoldServiceTokenProvider(ResourceLoader resourceLoader, String bookingUrl,
                                    Duration connectTimeout, Duration readTimeout,
                                    String issuer, String keyId, String privateKeyLocation, Clock clock) {
        this.resourceLoader = resourceLoader;
        this.issuer = issuer;
        this.keyId = keyId;
        this.privateKeyLocation = privateKeyLocation;
        this.clock = clock;
        this.bookingUrl = bookingUrl;
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalStateException("Booking hold delivery timeouts must be positive");
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        if (!bookingUrl.isBlank()) {
            builder.baseUrl(validateUrl(bookingUrl).toString());
        }
        this.bookingClient = builder.build();
    }

    public String createAuthorizationHeader() {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "RLC Booking-hold sender identity and Booking endpoint must be configured");
        }
        try {
            RSAPrivateCrtKey privateKey = readPrivateKey();
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            RSAKey jwk = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId).build();
            JwtEncoder encoder = new NimbusJwtEncoder(
                    new ImmutableJWKSet<SecurityContext>(new JWKSet(jwk)));
            Instant now = clock.instant();
            String token = encoder.encode(JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build(),
                    JwtClaimsSet.builder().issuer(issuer).subject(SUBJECT)
                            .audience(List.of(AUDIENCE)).issuedAt(now)
                            .expiresAt(now.plus(TOKEN_LIFETIME)).id(UUID.randomUUID().toString())
                            .claim("permissions", List.of(PERMISSION)).build())).getTokenValue();
            return "Bearer " + token;
        } catch (IOException | java.security.GeneralSecurityException | RuntimeException exception) {
            if (exception instanceof ResponseStatusException responseStatusException) {
                throw responseStatusException;
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "RLC Booking-hold signing key is unavailable");
        }
    }

    public boolean isConfigured() {
        return !bookingUrl.isBlank() && !issuer.isBlank() && !keyId.isBlank()
                && !privateKeyLocation.isBlank();
    }

    public RestClient bookingClient() {
        return bookingClient;
    }

    private RSAPrivateCrtKey readPrivateKey() throws IOException, java.security.GeneralSecurityException {
        String pem;
        try (var input = resourceLoader.getResource(privateKeyLocation).getInputStream()) {
            pem = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
        }
        String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        RSAPrivateKey key = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        if (!(key instanceof RSAPrivateCrtKey crtKey)) {
            throw new java.security.GeneralSecurityException("RSA private key must contain CRT parameters");
        }
        return crtKey;
    }

    private static URI validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute()
                    || !uri.getScheme().equalsIgnoreCase("https")
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalStateException("BOOKING_SERVICE_URL must be an absolute HTTPS URL");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("BOOKING_SERVICE_URL must be a valid absolute HTTPS URL", exception);
        }
    }
}
