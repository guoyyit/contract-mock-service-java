package com.github.guoyyit.contractmockservicejava.match;

import com.github.guoyyit.contractmockservicejava.model.Contract;
import com.github.guoyyit.contractmockservicejava.model.MockRequest;
import com.github.guoyyit.contractmockservicejava.store.Snapshot;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Matches a request to a contract by HTTP method and path template.
 */
@Component
public class RequestMatcher {

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    /**
     * @return the matching route, or {@code null} when nothing matches
     */
    public RouteMatch match(Snapshot snapshot, MockRequest request) {
        PathContainer pathContainer = PathContainer.parsePath(request.path());
        for (Contract contract : snapshot.contracts()) {
            if (!contract.method().equalsIgnoreCase(request.method())) {
                continue;
            }
            PathPattern pattern = PARSER.parse(contract.pathTemplate());
            PathPattern.PathMatchInfo info = pattern.matchAndExtract(pathContainer);
            if (info != null) {
                Map<String, String> variables = new LinkedHashMap<>(info.getUriVariables());
                return RouteMatch.of(contract, variables);
            }
        }
        return null;
    }

    /**
     * @return true when some contract path matches but the method does not
     */
    public boolean pathExistsWithOtherMethod(Snapshot snapshot, MockRequest request) {
        PathContainer pathContainer = PathContainer.parsePath(request.path());
        for (Contract contract : snapshot.contracts()) {
            if (contract.method().equalsIgnoreCase(request.method())) {
                continue;
            }
            if (PARSER.parse(contract.pathTemplate()).matches(pathContainer)) {
                return true;
            }
        }
        return false;
    }
}
