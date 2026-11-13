package teacommontea.clisto;

public record Markers(
        String unknown,
        String duplicate,
        String replaced,
        String failedStart,
        String failedEnd,
        String flagStart,
        String expectsBoolean,
        String expectsNumber,
        String expectsText,
        String expectsList,
        String expectsBraces,
        String expectsSection,
        String expectsValue,
        String misplaced,
        String misplacedTop,
        String misspelt) {

    public static final Markers ENGLISH = new Markers(
            "Unknown setting, disabled:",
            "Duplicate setting, disabled:",
            "Your previous value, which could not be used:",
            "THE FOLLOWING KEY(S) FAILED TO PARSE",
            "THE ABOVE KEY(S) FAILED TO PARSE",
            "THE FOLLOWING KEY NEEDS FIXING, THE DEFAULT IS USED UNTIL THEN",
            "BOOLEAN KEY ONLY WITH A VALUE OF TRUE OR FALSE",
            "NUMBER KEY ONLY WITH A NUMERIC VALUE",
            "TEXT KEY ONLY WITH A SINGLE TEXT VALUE",
            "LIST KEY ONLY WITH A LIST OF VALUES",
            "BRACE KEY ONLY WITH A VALUE INSIDE { }",
            "SECTION KEY ONLY WITH SETTINGS INDENTED BELOW IT",
            "SINGLE VALUE KEY ONLY, NOT A SECTION",
            "INDENTED UNDER THE WRONG SECTION, IT BELONGS UNDER %parent%",
            "INDENTED UNDER A SECTION, IT BELONGS AT THE TOP LEVEL",
            "UNKNOWN KEY, DID YOU MEAN %name%?");
}
