package com.playcity.bridge;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Date;

final class BridgeJwtVerifier {
    private final BridgeJwtConfig config;
    private final ConfigurableJWTProcessor<SecurityContext> processor;

    BridgeJwtVerifier(BridgeJwtConfig config) throws Exception {
        this.config = config;
        if (!config.valid()) {
            throw new IllegalArgumentException("JWT configuration is not valid.");
        }
        JWKSource<SecurityContext> keySource;
        if (config.jwksFile() != null) {
            JWKSet localKeys = JWKSet.load(config.jwksFile().toFile());
            if (localKeys.getKeys().isEmpty() || localKeys.getKeys().stream().anyMatch(key -> key.isPrivate())) {
                throw new IllegalArgumentException("JWT JWKS file must contain public verification keys only.");
            }
            keySource = new ImmutableJWKSet<>(localKeys);
        } else {
            keySource = JWKSourceBuilder.create(URI.create(config.jwksUrl()).toURL()).retrying(true).build();
        }

        DefaultJWTProcessor<SecurityContext> configuredProcessor = new DefaultJWTProcessor<>();
        configuredProcessor.setJWSTypeVerifier(
            new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType(config.requiredType()))
        );
        configuredProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(
            JWSAlgorithm.parse(config.algorithm()),
            keySource
        ));
        DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier = new DefaultJWTClaimsVerifier<>(
            Set.of(config.audience()),
            new JWTClaimsSet.Builder().issuer(config.issuer()).build(),
            Set.of("sub", "exp", "iat", config.scopeClaim()),
            Set.of()
        );
        claimsVerifier.setMaxClockSkew(config.clockSkewSeconds());
        configuredProcessor.setJWTClaimsSetVerifier(claimsVerifier);
        this.processor = configuredProcessor;
    }

    Verification verify(String token) {
        if (token == null || token.length() > config.maxTokenChars() || token.chars().filter(ch -> ch == '.').count() != 2L) {
            return Verification.invalid();
        }
        try {
            JWTClaimsSet claims = processor.process(token, null);
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank() || subject.length() > 512) {
                return Verification.invalid();
            }
            Date issuedAt = claims.getIssueTime();
            Date expiresAt = claims.getExpirationTime();
            long now = System.currentTimeMillis();
            long skewMillis = config.clockSkewSeconds() * 1000L;
            if (issuedAt == null
                || expiresAt == null
                || issuedAt.getTime() > now + skewMillis
                || expiresAt.getTime() <= issuedAt.getTime()
                || expiresAt.getTime() - issuedAt.getTime() > config.maxLifetimeSeconds() * 1000L) {
                return Verification.invalid();
            }
            String clientId = stringClaim(claims, config.clientIdClaim());
            Set<String> tokenScopes = scopes(claims.getClaim(config.scopeClaim()));
            Set<String> tokenActions = mappedRules(tokenScopes, config.actionScopePrefix());
            Set<String> tokenEvents = mappedRules(tokenScopes, config.eventScopePrefix());
            JwtPrincipal principal = new JwtPrincipal(
                callerId(clientId, subject),
                BridgeScopeRules.intersect(config.allowedActions(), tokenActions),
                BridgeScopeRules.intersect(config.allowedEvents(), tokenEvents)
            );
            return Verification.valid(principal);
        } catch (BadJOSEException | ParseException exception) {
            return Verification.invalid();
        } catch (JOSEException exception) {
            return Verification.unavailable();
        }
    }

    private static String stringClaim(JWTClaimsSet claims, String name) throws ParseException {
        String value = claims.getStringClaim(name);
        return value == null ? "" : value.trim();
    }

    private static Set<String> scopes(Object raw) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (raw instanceof String string) {
            for (String value : string.trim().split("\\s+")) {
                if (!value.isBlank()) {
                    result.add(value);
                }
            }
        } else if (raw instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (value instanceof String string && !string.isBlank()) {
                    result.add(string.trim());
                }
            }
        }
        return Set.copyOf(result);
    }

    private static Set<String> mappedRules(Set<String> scopes, String prefix) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (!scope.startsWith(prefix)) {
                continue;
            }
            String rule = scope.substring(prefix.length()).toLowerCase(Locale.ROOT);
            if (BridgeScopeRules.validRule(rule)) {
                result.add(rule);
            }
        }
        return Set.copyOf(result);
    }

    private static String callerId(String rawClientId, String subject) {
        String clientId = rawClientId.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (clientId.isBlank()) {
            clientId = "client";
        }
        if (clientId.length() > 32) {
            clientId = clientId.substring(0, 32);
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        return "jwt:" + clientId + ":" + HexFormat.of().formatHex(digest, 0, 6);
    }

    record JwtPrincipal(String callerId, Set<String> allowedActions, Set<String> allowedEvents) {
        JwtPrincipal {
            allowedActions = Set.copyOf(allowedActions);
            allowedEvents = Set.copyOf(allowedEvents);
        }
    }

    record Verification(Status status, JwtPrincipal principal) {
        static Verification valid(JwtPrincipal principal) {
            return new Verification(Status.VALID, principal);
        }

        static Verification invalid() {
            return new Verification(Status.INVALID, null);
        }

        static Verification unavailable() {
            return new Verification(Status.UNAVAILABLE, null);
        }
    }

    enum Status {
        VALID,
        INVALID,
        UNAVAILABLE
    }
}
