package com.example.mydnd.changeset;

import com.example.mydnd.director.DirectorResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChangeSetExecutionReport {
    private final List<DirectorResult> results;
    private final String parseError;

    private ChangeSetExecutionReport(List<DirectorResult> results, String parseError) {
        this.results = results == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(results));
        this.parseError = parseError == null ? "" : parseError;
    }

    public static ChangeSetExecutionReport completed(List<DirectorResult> results) {
        return new ChangeSetExecutionReport(results, "");
    }

    public static ChangeSetExecutionReport parseFailed(String code) {
        return new ChangeSetExecutionReport(Collections.emptyList(), code);
    }

    public List<DirectorResult> getResults() { return results; }
    public String getParseError() { return parseError; }
    public boolean hasParseError() { return !parseError.isEmpty(); }
}
