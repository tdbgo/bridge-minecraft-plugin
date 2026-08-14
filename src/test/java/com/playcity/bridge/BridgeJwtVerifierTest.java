package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeJwtVerifierTest {
    @TempDir
    Path tempDirectory;

    @Test
    void verifiesPinnedJwtAndIntersectsTokenAndLocalScopes() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        Path jwks = tempDirectory.resolve("jwks.json");
        Files.writeString(jwks, new JWKSet(key.toPublicJWK()).toString());
        BridgeJwtVerifier verifier = new BridgeJwtVerifier(config(jwks));

        String token = token(
            key,
            "bridge:action:minecraft.player_status bridge:action:minecraft.teleport_player "
                + "bridge:event:minecraft.player_joined"
        );
        BridgeJwtVerifier.Verification verification = verifier.verify(token);

        assertEquals(BridgeJwtVerifier.Status.VALID, verification.status());
        assertEquals(Set.of("minecraft.player_status"), verification.principal().allowedActions());
        assertEquals(Set.of("minecraft.player_joined"), verification.principal().allowedEvents());

        BridgeHttpAccessController controller = new BridgeHttpAccessController(List.of(), config(jwks), Logger.getAnonymousLogger());
        BridgeHttpAccessController.Authentication authentication = controller.authenticate(token);
        assertEquals(true, authentication.authenticated());
        assertEquals(true, authentication.session().caller().allows("minecraft.player_status"));
        assertEquals(false, authentication.session().caller().allows("minecraft.teleport_player"));
        assertEquals(true, authentication.session().allowsEvent("minecraft.player_joined"));
    }

    @Test
    void rejectsWrongAudienceAndMissingIssuedAt() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        Path jwks = tempDirectory.resolve("jwks.json");
        Files.writeString(jwks, new JWKSet(key.toPublicJWK()).toString());
        BridgeJwtVerifier verifier = new BridgeJwtVerifier(config(jwks));

        SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(new JOSEObjectType("at+jwt"))
                .keyID(key.getKeyID())
                .build(),
            new JWTClaimsSet.Builder()
                .issuer("https://issuer.example")
                .audience("wrong-audience")
                .subject("user-1")
                .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                .claim("scope", "bridge:action:minecraft.player_status")
                .build()
        );
        jwt.sign(new RSASSASigner(key));

        assertEquals(BridgeJwtVerifier.Status.INVALID, verifier.verify(jwt.serialize()).status());
    }

    @Test
    void rejectsFutureIssuedAtAndExcessiveTokenLifetime() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        Path jwks = tempDirectory.resolve("jwks.json");
        Files.writeString(jwks, new JWKSet(key.toPublicJWK()).toString());
        BridgeJwtVerifier verifier = new BridgeJwtVerifier(config(jwks));
        Instant now = Instant.now();

        assertEquals(
            BridgeJwtVerifier.Status.INVALID,
            verifier.verify(token(key, now.plusSeconds(120), now.plusSeconds(180))).status()
        );
        assertEquals(
            BridgeJwtVerifier.Status.INVALID,
            verifier.verify(token(key, now, now.plusSeconds(3601))).status()
        );
    }

    private static BridgeJwtConfig config(Path jwks) {
        return new BridgeJwtConfig(
            true,
            "",
            "https://issuer.example",
            "bridge-api",
            "",
            jwks,
            "RS256",
            "at+jwt",
            "scope",
            "client_id",
            "bridge:action:",
            "bridge:event:",
            Set.of("minecraft.player_status"),
            Set.of("minecraft.player_*"),
            120,
            4,
            2,
            30,
            3600,
            8192,
            128,
            900_000L
        );
    }

    private static String token(RSAKey key, String scope) throws Exception {
        Instant now = Instant.now();
        return token(key, now, now.plusSeconds(60), scope);
    }

    private static String token(RSAKey key, Instant issuedAt, Instant expiresAt) throws Exception {
        return token(key, issuedAt, expiresAt, "bridge:action:minecraft.player_status");
    }

    private static String token(RSAKey key, Instant issuedAt, Instant expiresAt, String scope) throws Exception {
        SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(new JOSEObjectType("at+jwt"))
                .keyID(key.getKeyID())
                .build(),
            new JWTClaimsSet.Builder()
                .issuer("https://issuer.example")
                .audience("bridge-api")
                .subject("user-1")
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("client_id", "maps")
                .claim("scope", scope)
                .build()
        );
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
