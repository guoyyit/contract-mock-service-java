package com.github.guoyyit.contractmockservicejava.docs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.contractmockservicejava.model.BodySpec;
import com.github.guoyyit.contractmockservicejava.model.Contract;
import com.github.guoyyit.contractmockservicejava.model.ErrorCode;
import com.github.guoyyit.contractmockservicejava.model.FieldSpec;
import com.github.guoyyit.contractmockservicejava.model.ParamSpec;
import com.github.guoyyit.contractmockservicejava.model.ResponseStep;
import com.github.guoyyit.contractmockservicejava.model.Scenario;
import com.github.guoyyit.contractmockservicejava.model.ScenarioSelector;
import com.github.guoyyit.contractmockservicejava.store.ContractStore;
import com.github.guoyyit.contractmockservicejava.store.Snapshot;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Generates interface documentation strictly from the active snapshot, so
 * docs and responses never diverge. Every commit (which creates a new
 * snapshot) is reflected immediately on the next read.
 */
@Service
public class DocsService {

    private final ContractStore store;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DocsService(ContractStore store) {
        this.store = store;
    }

    /** Human-readable Markdown documentation for the current version. */
    public String markdown() {
        Snapshot snapshot = store.current();
        StringBuilder sb = new StringBuilder();
        sb.append("# Contract Mock Service\n\n");
        sb.append("Configuration version: `").append(snapshot.version()).append("`\n\n");
        if (snapshot.config() != null && snapshot.config().note() != null) {
            sb.append("> ").append(snapshot.config().note()).append("\n\n");
        }
        sb.append("## Interfaces\n\n");
        for (Contract contract : snapshot.contracts()) {
            appendContract(sb, contract);
        }
        appendErrorCodes(sb);
        return sb.toString();
    }

    private void appendContract(StringBuilder sb, Contract contract) {
        sb.append("### `").append(contract.method().toUpperCase()).append(' ')
                .append(contract.pathTemplate()).append("`\n\n");
        sb.append("- Contract id: `").append(contract.id()).append("`\n");
        sb.append("- Default scenario: `").append(contract.defaultScenario()).append("`\n\n");

        appendParams(sb, "Query parameters", contract.queryParams());
        appendParams(sb, "Headers", contract.headers());
        appendBody(sb, contract.body());
        appendSelectors(sb, contract.selectors());

        sb.append("#### Scenarios\n\n");
        for (Scenario scenario : contract.scenarios()) {
            appendScenario(sb, scenario);
        }
        sb.append('\n');
    }

    private void appendParams(StringBuilder sb, String title, List<ParamSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return;
        }
        sb.append("#### ").append(title).append("\n\n");
        sb.append("| Name | Required | Pattern | Equals |\n");
        sb.append("| --- | --- | --- | --- |\n");
        for (ParamSpec spec : specs) {
            sb.append("| `").append(spec.name()).append("` | ")
                    .append(spec.required()).append(" | ")
                    .append(na(spec.pattern())).append(" | ")
                    .append(na(spec.equals())).append(" |\n");
        }
        sb.append('\n');
    }

    private void appendBody(StringBuilder sb, BodySpec body) {
        if (body == null) {
            return;
        }
        sb.append("#### Request body\n\n");
        sb.append("- Body required: ").append(body.required()).append('\n');
        if (body.fields() != null && !body.fields().isEmpty()) {
            sb.append("\n| Field | Type | Required | Pattern | Equals |\n");
            sb.append("| --- | --- | --- | --- | --- |\n");
            for (FieldSpec field : body.fields()) {
                sb.append("| `").append(field.path()).append("` | ")
                        .append(na(field.type())).append(" | ")
                        .append(field.required()).append(" | ")
                        .append(na(field.pattern())).append(" | ")
                        .append(na(field.equals())).append(" |\n");
            }
        }
        sb.append('\n');
    }

    private void appendSelectors(StringBuilder sb, List<ScenarioSelector> selectors) {
        if (selectors == null || selectors.isEmpty()) {
            return;
        }
        sb.append("#### Scenario selection\n\n");
        sb.append("| Source | Name | Equals | Scenario |\n");
        sb.append("| --- | --- | --- | --- |\n");
        for (ScenarioSelector selector : selectors) {
            sb.append("| ").append(selector.source()).append(" | `")
                    .append(selector.name()).append("` | ")
                    .append(na(selector.equals())).append(" | `")
                    .append(selector.scenario()).append("` |\n");
        }
        sb.append('\n');
    }

    private void appendScenario(StringBuilder sb, Scenario scenario) {
        sb.append("##### Scenario `").append(scenario.name()).append("`");
        if (scenario.repeatLast()) {
            sb.append(" _(repeats last step after sequence)_");
        }
        sb.append("\n\n");
        sb.append("| Call # | Status | Error | Error code | Delay (ms) | Response body |\n");
        sb.append("| --- | --- | --- | --- | --- | --- |\n");
        List<ResponseStep> steps = scenario.steps();
        for (int i = 0; i < steps.size(); i++) {
            ResponseStep step = steps.get(i);
            sb.append("| ").append(i + 1).append(" | ")
                    .append(step.status()).append(" | ")
                    .append(step.error()).append(" | ")
                    .append(na(step.errorCode())).append(" | ")
                    .append(step.delayMillis()).append(" | ")
                    .append(escapeCell(toJson(step.body()))).append(" |\n");
        }
        sb.append('\n');
    }

    private void appendErrorCodes(StringBuilder sb) {
        sb.append("## Error codes\n\n");
        sb.append("All errors use the envelope `{ \"error\": { \"code\", \"message\" } }`.\n\n");
        sb.append("| Code | HTTP status | Meaning |\n");
        sb.append("| --- | --- | --- |\n");
        for (ErrorCode code : ErrorCode.values()) {
            sb.append("| `").append(code.code()).append("` | ")
                    .append(code.httpStatus()).append(" | ")
                    .append(meaning(code)).append(" |\n");
        }
        sb.append('\n');
    }

    private String meaning(ErrorCode code) {
        return switch (code) {
            case NO_MATCH -> "No contract matches the method and path";
            case METHOD_NOT_ALLOWED -> "Path exists but method is not allowed";
            case INVALID_QUERY -> "A query parameter failed structural validation";
            case INVALID_HEADER -> "A header failed structural validation";
            case INVALID_BODY -> "A body field failed structural validation";
            case INVALID_SCENARIO_SELECTOR -> "Scenario could not be resolved";
            case REQUEST_TIMEOUT -> "The request exceeded its time budget";
            case REQUEST_CANCELLED -> "The request was cancelled while waiting";
            case SCENARIO_EXHAUSTED -> "Scenario steps are consumed and not repeating";
            case INVALID_CONTRACT -> "The submitted configuration set is invalid";
            case CONFLICT -> "Conflicting update";
            case INTERNAL_ERROR -> "Unexpected internal failure";
        };
    }

    private String toJson(Object body) {
        if (body == null) {
            return "";
        }
        if (body instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            return String.valueOf(body);
        }
    }

    private String escapeCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\n", " ");
    }

    private String na(String value) {
        return value == null || value.isBlank() ? "_n/a_" : "`" + value + "`";
    }
}
