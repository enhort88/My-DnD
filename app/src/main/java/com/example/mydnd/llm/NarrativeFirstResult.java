package com.example.mydnd.llm;

/** Splits the native narrative-first result into visible narrative and hidden ChangeSet. */
public final class NarrativeFirstResult {

    public static final String SEPARATOR = "\n__MYDND_CHANGESET__\n";

    private final String narrative;
    private final String rawChangeSet;

    private NarrativeFirstResult(String narrative, String rawChangeSet) {
        this.narrative = narrative == null ? "" : narrative;
        this.rawChangeSet = rawChangeSet == null ? "" : rawChangeSet;
    }

    public static NarrativeFirstResult parse(String fullText) {
        String safe = fullText == null ? "" : fullText;
        int marker = safe.indexOf(SEPARATOR);
        if (marker < 0) {
            return new NarrativeFirstResult(safe, "");
        }
        return new NarrativeFirstResult(
                safe.substring(0, marker),
                safe.substring(marker + SEPARATOR.length())
        );
    }

    public String getNarrative() { return narrative; }
    public String getRawChangeSet() { return rawChangeSet; }
}
