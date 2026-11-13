package teacommontea.clisto;

public record Action(Kind kind, String file, int line, String key, String target) {

    public enum Kind {
        FIXED,
        COERCED,
        REMOVED,
        QUARANTINED,
        DUPLICATE,
        INVALID,
        UNPARSABLE,
        FLAGGED
    }

    public boolean isWarning() {
        return kind == Kind.QUARANTINED || kind == Kind.DUPLICATE
                || kind == Kind.INVALID || kind == Kind.UNPARSABLE || kind == Kind.FLAGGED;
    }
}
