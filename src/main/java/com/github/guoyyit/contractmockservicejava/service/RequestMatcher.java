package com.github.guoyyit.contractmockservicejava.service;

import com.github.guoyyit.contractmockservicejava.model.EndpointContract;
import com.github.guoyyit.contractmockservicejava.model.MatchResult;
import com.github.guoyyit.contractmockservicejava.store.ActiveRevision;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Service;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PathPattern.PathMatchInfo;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Matches an incoming method+path against the contracts of one revision.
 *
 * <p>Path templates follow Spring's {@code PathPattern} syntax ({@code /orders/{id}}).
 * If a path matches but the HTTP method does not, a distinguishable 405 result (with the
 * allowed methods) is returned instead of a generic 404.
 */
@Service
public class RequestMatcher {

    private final PathPatternParser parser = PathPatternParser.defaultInstance;

    public MatchResult match(ActiveRevision revision, String method, String path) {
        if (revision == null || revision.config().endpoints() == null) {
            return null;
        }
        PathContainer pathContainer = PathContainer.parsePath(path);
        Set<String> allowed = new LinkedHashSet<>();

        for (EndpointContract endpoint : revision.config().endpoints()) {
            if (endpoint.pathTemplate() == null) {
                continue;
            }
            PathPattern pattern = parser.parse(endpoint.pathTemplate());
            PathMatchInfo info = pattern.matchAndExtract(pathContainer);
            if (info == null) {
                continue;
            }
            boolean methodMatches = endpoint.method() != null
                    && endpoint.method().equalsIgnoreCase(method);
            if (methodMatches) {
                return new MatchResult(endpoint, info.getUriVariables(), false, null);
            }
            if (endpoint.method() != null) {
                allowed.add(endpoint.method().toUpperCase());
            }
        }

        if (!allowed.isEmpty()) {
            return new MatchResult(null, Map.of(), true, String.join(", ", allowed));
        }
        return null;
    }
}
