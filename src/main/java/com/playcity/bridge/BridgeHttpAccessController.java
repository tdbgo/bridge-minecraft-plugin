package com.playcity.bridge;

import com.playcity.bridge.api.BridgeCaller;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

final class BridgeHttpAccessController {
    private static final int MAX_CREDENTIAL_CHARS = 32_768;
    private final List<StaticCredentialState> credentials;
    private final BridgeJwtConfig jwtConfig;
    private final BridgeJwtVerifier jwtVerifier;
    private final String jwtIssue;
    private final Map<String, AccessState> jwtStates = new LinkedHashMap<>();

    BridgeHttpAccessController(List<BridgeHttpCredential> credentials) {
        this(credentials, null, null);
    }

    BridgeHttpAccessController(List<BridgeHttpCredential> credentials, BridgeJwtConfig jwtConfig, Logger logger) {
        this.credentials = credentials.stream().map(StaticCredentialState::new).toList();
        this.jwtConfig = jwtConfig;
        BridgeJwtVerifier verifier = null;
        String issue = "";
        if (jwtConfig != null && jwtConfig.enabled()) {
            if (!jwtConfig.valid()) {
                issue = jwtConfig.issue();
            } else {
                try {
                    verifier = new BridgeJwtVerifier(jwtConfig);
                } catch (Exception exception) {
                    issue = "jwt_verifier_initialization_failed";
                    if (logger != null) {
                        logger.log(Level.WARNING, "Bridge JWT verifier could not be initialized.", exception);
                    }
                }
            }
        }
        this.jwtVerifier = verifier;
        this.jwtIssue = issue;
    }

    Authentication authenticate(String providedToken) {
        if (providedToken != null && providedToken.length() > MAX_CREDENTIAL_CHARS) {
            return Authentication.denied(401, "unauthorized", "Bridge credential is invalid.");
        }
        StaticCredentialState matched = null;
        byte[] provided = bytes(providedToken);
        for (StaticCredentialState candidate : credentials) {
            if (MessageDigest.isEqual(bytes(candidate.credential.token()), provided)) {
                matched = candidate;
            }
        }
        if (matched != null) {
            return Authentication.allowed(new Session(
                matched.state,
                matched.credential.caller(),
                matched.credential.allowedEvents()
            ));
        }
        if (jwtConfig == null || !jwtConfig.enabled() || !looksLikeJwt(providedToken)) {
            return Authentication.denied(401, "unauthorized", "Bridge credential is invalid.");
        }
        if (jwtVerifier == null) {
            return Authentication.denied(503, "jwt_verification_unavailable", "Bridge JWT verification is unavailable.");
        }
        BridgeJwtVerifier.Verification verification = jwtVerifier.verify(providedToken);
        if (verification.status() == BridgeJwtVerifier.Status.INVALID) {
            return Authentication.denied(401, "unauthorized", "Bridge credential is invalid.");
        }
        if (verification.status() == BridgeJwtVerifier.Status.UNAVAILABLE) {
            return Authentication.denied(503, "jwt_verification_unavailable", "Bridge JWT verification is unavailable.");
        }
        BridgeJwtVerifier.JwtPrincipal principal = verification.principal();
        long now = System.currentTimeMillis();
        AccessState state = jwtState(principal.callerId(), now);
        if (state == null) {
            return Authentication.denied(503, "jwt_identity_capacity_exceeded", "Bridge JWT identity capacity is full.");
        }
        BridgeCaller caller = new BridgeCaller(
            principal.callerId(),
            BridgeCaller.Type.JWT,
            principal.allowedActions()
        );
        return Authentication.allowed(new Session(state, caller, principal.allowedEvents()));
    }

    Authorization authorize(Session session, String actionName, long nowMillis) {
        if (!session.caller.allows(actionName)) {
            return Authorization.denied(403, "action_forbidden", "Caller is not allowed to execute this Bridge action.");
        }
        return acquire(session, nowMillis);
    }

    Authorization authorizeDiscovery(Session session, long nowMillis) {
        return acquire(session, nowMillis);
    }

    EventAuthorization authorizeEventConnection(Session session, long nowMillis) {
        if (session.allowedEvents.isEmpty()) {
            return EventAuthorization.denied(403, "event_forbidden", "Caller is not allowed to receive Bridge events.");
        }
        AcquireResult rate = session.state.tryConsumeRate(nowMillis);
        if (rate == AcquireResult.RATE_LIMITED) {
            return EventAuthorization.denied(429, "rate_limited", "Bridge caller request rate was exceeded.");
        }
        if (!session.state.tryAcquireEventConnection()) {
            return EventAuthorization.denied(429, "too_many_event_connections", "Bridge caller has too many event connections.");
        }
        return EventAuthorization.allowed(new EventPermit(session));
    }

    boolean authorizeEventSubscription(Session session, Set<String> requestedRules) {
        return !requestedRules.isEmpty()
            && BridgeScopeRules.requestedRulesAllowed(requestedRules, session.allowedEvents);
    }

    boolean consumeEventMessage(Session session, long nowMillis) {
        return session.state.tryConsumeRate(nowMillis) == AcquireResult.ACQUIRED;
    }

    String jwtIssue() {
        return jwtIssue;
    }

    private Authorization acquire(Session session, long nowMillis) {
        AcquireResult acquired = session.state.tryAcquireRequest(nowMillis);
        if (acquired == AcquireResult.RATE_LIMITED) {
            return Authorization.denied(429, "rate_limited", "Bridge caller request rate was exceeded.");
        }
        if (acquired == AcquireResult.SATURATED) {
            return Authorization.denied(429, "too_many_concurrent_requests", "Bridge caller has too many active requests.");
        }
        return Authorization.allowed(new Permit(session));
    }

    private synchronized AccessState jwtState(String callerId, long nowMillis) {
        Iterator<Map.Entry<String, AccessState>> iterator = jwtStates.entrySet().iterator();
        while (iterator.hasNext()) {
            AccessState state = iterator.next().getValue();
            if (nowMillis - state.lastSeenMillis > jwtConfig.identityTtlMillis() && state.idle()) {
                iterator.remove();
            }
        }
        AccessState existing = jwtStates.get(callerId);
        if (existing != null) {
            existing.touch(nowMillis);
            return existing;
        }
        if (jwtStates.size() >= jwtConfig.maxIdentities()) {
            return null;
        }
        AccessState created = new AccessState(
            jwtConfig.requestsPerMinute(),
            jwtConfig.maxConcurrent(),
            jwtConfig.maxEventConnections()
        );
        created.touch(nowMillis);
        jwtStates.put(callerId, created);
        return created;
    }

    record Authentication(Session session, int status, String code, String message) {
        static Authentication allowed(Session session) {
            return new Authentication(session, 0, "", "");
        }

        static Authentication denied(int status, String code, String message) {
            return new Authentication(null, status, code, message);
        }

        boolean authenticated() {
            return session != null;
        }
    }

    static final class Session {
        private final AccessState state;
        private final BridgeCaller caller;
        private final Set<String> allowedEvents;

        private Session(AccessState state, BridgeCaller caller, Set<String> allowedEvents) {
            this.state = state;
            this.caller = caller;
            this.allowedEvents = Set.copyOf(allowedEvents);
        }

        BridgeCaller caller() {
            return caller;
        }

        Set<String> allowedEvents() {
            return allowedEvents;
        }

        boolean allowsEvent(String eventName) {
            return BridgeScopeRules.matches(allowedEvents, eventName);
        }
    }

    record Authorization(Permit permit, int status, String code, String message) {
        static Authorization allowed(Permit permit) {
            return new Authorization(permit, 0, "", "");
        }

        static Authorization denied(int status, String code, String message) {
            return new Authorization(null, status, code, message);
        }

        boolean allowed() {
            return permit != null;
        }
    }

    record EventAuthorization(EventPermit permit, int status, String code, String message) {
        static EventAuthorization allowed(EventPermit permit) {
            return new EventAuthorization(permit, 0, "", "");
        }

        static EventAuthorization denied(int status, String code, String message) {
            return new EventAuthorization(null, status, code, message);
        }

        boolean allowed() {
            return permit != null;
        }
    }

    static final class Permit implements AutoCloseable {
        private final Session session;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(Session session) {
            this.session = session;
        }

        BridgeCaller caller() {
            return session.caller;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                session.state.concurrent.release();
            }
        }
    }

    static final class EventPermit implements AutoCloseable {
        private final Session session;
        private final AtomicBoolean closed = new AtomicBoolean();

        private EventPermit(Session session) {
            this.session = session;
        }

        Session session() {
            return session;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                session.state.eventConnections.decrementAndGet();
            }
        }
    }

    private enum AcquireResult {
        ACQUIRED,
        RATE_LIMITED,
        SATURATED
    }

    private static final class StaticCredentialState {
        private final BridgeHttpCredential credential;
        private final AccessState state;

        private StaticCredentialState(BridgeHttpCredential credential) {
            this.credential = credential;
            this.state = new AccessState(
                credential.requestsPerMinute(),
                credential.maxConcurrent(),
                credential.maxEventConnections()
            );
        }
    }

    private static final class AccessState {
        private final int requestsPerMinute;
        private final Semaphore concurrent;
        private final int maxConcurrent;
        private final int maxEventConnections;
        private final AtomicInteger eventConnections = new AtomicInteger();
        private long windowStartedAtMillis;
        private int requestsInWindow;
        private volatile long lastSeenMillis;

        private AccessState(int requestsPerMinute, int maxConcurrent, int maxEventConnections) {
            this.requestsPerMinute = requestsPerMinute;
            this.concurrent = new Semaphore(maxConcurrent);
            this.maxConcurrent = maxConcurrent;
            this.maxEventConnections = maxEventConnections;
        }

        private synchronized AcquireResult tryConsumeRate(long nowMillis) {
            lastSeenMillis = nowMillis;
            if (windowStartedAtMillis == 0L || nowMillis - windowStartedAtMillis >= 60_000L) {
                windowStartedAtMillis = nowMillis;
                requestsInWindow = 0;
            }
            if (requestsInWindow >= requestsPerMinute) {
                return AcquireResult.RATE_LIMITED;
            }
            requestsInWindow++;
            return AcquireResult.ACQUIRED;
        }

        private AcquireResult tryAcquireRequest(long nowMillis) {
            AcquireResult rate = tryConsumeRate(nowMillis);
            if (rate != AcquireResult.ACQUIRED) {
                return rate;
            }
            return concurrent.tryAcquire() ? AcquireResult.ACQUIRED : AcquireResult.SATURATED;
        }

        private boolean tryAcquireEventConnection() {
            if (maxEventConnections <= 0) {
                return false;
            }
            while (true) {
                int current = eventConnections.get();
                if (current >= maxEventConnections) {
                    return false;
                }
                if (eventConnections.compareAndSet(current, current + 1)) {
                    return true;
                }
            }
        }

        private void touch(long nowMillis) {
            lastSeenMillis = nowMillis;
        }

        private boolean idle() {
            return concurrent.availablePermits() == maxConcurrent && eventConnections.get() == 0;
        }
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean looksLikeJwt(String value) {
        return value != null && value.chars().filter(ch -> ch == '.').count() == 2L;
    }
}
