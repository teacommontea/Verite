package teacommontea.clisto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ConfigMerge {

    enum Mode { REPAIR, FULL }

    enum Status { UNCHANGED, CHANGED, BROKEN, UNSAFE, UNREADABLE }

    record Result(Status status, List<String> lines, List<Action> actions, String reason) {}

    private enum Fate { LIVE, COMMENTED, DELETED, FLAGGED }

    private static final Pattern MISSING_SPACE = Pattern.compile("^(\\s*)([^\\s#\"'\\-][^:#\"']*?):([^\\s/].*)$");

    private final String file;
    private final Mode mode;
    private final Syntax syntax;
    private final Markers markers;
    private final YamlDoc theirs;
    private final YamlDoc base;
    private final List<Action> actions = new ArrayList<>();
    private boolean syntaxAvailable;

    private YamlDoc ours;
    private Edits edits;
    private Fate[] fate;
    private final Set<String> claimed = new HashSet<>();
    private final Map<String, String> expectedValues = new LinkedHashMap<>();
    private final List<String> expectedComments = new ArrayList<>();
    private final Set<Integer> invalid = new HashSet<>();
    private int[] origin = new int[0];

    private ConfigMerge(String file, Mode mode, Syntax syntax, Markers markers, String bundledText, String baseText) {
        this.file = file;
        this.mode = mode;
        this.syntax = syntax;
        this.syntaxAvailable = syntax != null;
        this.markers = markers;
        this.theirs = YamlDoc.parse(bundledText);
        this.base = baseText == null ? null : YamlDoc.parse(baseText);
    }

    static Result run(String file, String deployedText, String bundledText, String baseText,
                      Mode mode, Syntax syntax, Markers markers) {
        return new ConfigMerge(file, mode, syntax, markers, bundledText, baseText).run(deployedText);
    }

    private Result run(String deployedText) {
        List<String> original = YamlText.splitLines(deployedText);
        if (deployedText.trim().isEmpty()) {
            return new Result(Status.UNREADABLE, theirs.lines(), actions, "empty");
        }
        List<String> work = repairLines(stripMarkers(original));
        List<int[]> broken = brokenSpans(work);
        if (broken == null) {
            return new Result(Status.UNSAFE, original, actions, "syntax");
        }
        if (!broken.isEmpty()) {
            return new Result(Status.BROKEN, markBroken(work, broken), actions, null);
        }
        ours = YamlDoc.parse(work);
        boolean recognised = false;
        for (YamlDoc.Entry e : ours.entries()) {
            if (theirs.has(e.path())) {
                recognised = true;
                break;
            }
        }
        if (!recognised) {
            return new Result(Status.UNREADABLE, theirs.lines(), actions, "no known settings");
        }

        edits = new Edits(work);
        fate = new Fate[ours.entries().size()];
        java.util.Arrays.fill(fate, Fate.LIVE);

        commentDuplicates();
        flagKindChanges();
        flagMisplaced();
        if (mode == Mode.FULL) {
            resolveUnknown();
            insertMissing();
            updateRegions();
        }
        mergeValues();

        List<String> out = edits.apply();
        String failure = verify(out);
        if (failure != null) {
            return new Result(Status.UNSAFE, original, actions, failure);
        }
        return new Result(out.equals(original) ? Status.UNCHANGED : Status.CHANGED, out, actions, null);
    }

    private void act(Action.Kind kind, int line, String key, String target) {
        int shown = line >= 0 && line < origin.length ? origin[line] : line;
        actions.add(new Action(kind, file, shown + 1, key, target));
    }

    static String marker(String text) {
        return "# !!! " + text + " !!!";
    }

    static boolean isMarker(String line) {
        String t = line.trim();
        return t.startsWith("# !!! ") && t.endsWith(" !!!") && t.length() > 10;
    }

    private List<String> stripMarkers(List<String> original) {
        List<String> kept = new ArrayList<>();
        List<Integer> from = new ArrayList<>();
        for (int i = 0; i < original.size(); i++) {
            if (!isMarker(original.get(i))) {
                kept.add(original.get(i));
                from.add(i);
            }
        }
        origin = new int[from.size()];
        for (int i = 0; i < origin.length; i++) {
            origin[i] = from.get(i);
        }
        return kept;
    }

    private List<int[]> brokenSpans(List<String> work) {
        if (!syntaxAvailable) {
            return List.of();
        }
        List<String> probe = new ArrayList<>(work);
        for (int i : invalid) {
            probe.set(i, "#");
        }
        YamlDoc doc = YamlDoc.parse(work);
        List<int[]> spans = new ArrayList<>();
        for (int guard = 0; guard < 32; guard++) {
            if (parses(joined(probe))) {
                spans.sort((a, b) -> Integer.compare(a[0], b[0]));
                return spans;
            }
            int fail = firstFailingLine(probe);
            int[] span = spanAt(doc, work, fail);
            if (span == null) {
                return null;
            }
            for (int widen = 0; widen < 3; widen++) {
                List<String> trial = blanked(probe, span);
                if (parses(joined(trial.subList(0, Math.min(trial.size(), fail + 1))))) {
                    break;
                }
                int[] previous = spanAt(doc, work, span[0] - 1);
                if (previous == null) {
                    break;
                }
                span = new int[] {previous[0], span[1]};
            }
            probe = blanked(probe, span);
            spans.add(span);
        }
        return null;
    }

    private int firstFailingLine(List<String> probe) {
        int lo = 0;
        int hi = probe.size();
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (parses(joined(probe.subList(0, mid)))) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return hi - 1;
    }

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    private static List<String> blanked(List<String> lines, int[] span) {
        List<String> out = new ArrayList<>(lines);
        for (int i = span[0]; i < span[1]; i++) {
            out.set(i, "#");
        }
        return out;
    }

    private static int[] spanAt(YamlDoc doc, List<String> work, int line) {
        int at = line;
        while (at >= 0 && (YamlText.isBlank(work.get(at)) || YamlText.isComment(work.get(at)))) {
            at--;
        }
        if (at < 0) {
            return null;
        }
        for (YamlDoc.Entry e : doc.entries()) {
            int end = e.isParent() ? e.keyLine() + 1 : e.valueEnd();
            if (at >= e.keyLine() && at < end) {
                return new int[] {e.keyLine(), end};
            }
        }
        return new int[] {at, at + 1};
    }

    private List<String> markBroken(List<String> work, List<int[]> spans) {
        List<String> out = new ArrayList<>();
        int next = 0;
        for (int[] span : spans) {
            if (span[0] < next) {
                continue;
            }
            out.addAll(work.subList(next, span[0]));
            String pad = YamlText.spaces(YamlText.indentOf(work.get(span[0])));
            out.add(pad + marker(markers.failedStart()));
            int shown = out.size();
            out.addAll(work.subList(span[0], span[1]));
            out.add(pad + marker(markers.failedEnd()));
            actions.add(new Action(Action.Kind.UNPARSABLE, file, shown + 1, keyOf(work.get(span[0])), null));
            next = span[1];
        }
        out.addAll(work.subList(next, work.size()));
        return out;
    }

    private boolean parses(String text) {
        if (!syntaxAvailable) {
            return true;
        }
        try {
            syntax.check(YamlText.protectBraces(text));
            return true;
        } catch (LinkageError e) {
            syntaxAvailable = false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> repairLines(List<String> original) {
        Set<String> known = new HashSet<>();
        for (YamlDoc.Entry e : theirs.entries()) {
            known.add(e.name());
        }
        List<String> work = new ArrayList<>(original);
        for (int i = 0; i < work.size(); i++) {
            String line = work.get(i);
            String fixed = line;
            int lead = 0;
            while (lead < fixed.length() && (fixed.charAt(lead) == ' ' || fixed.charAt(lead) == '\t')) {
                lead++;
            }
            if (fixed.substring(0, lead).indexOf('\t') >= 0) {
                fixed = fixed.substring(0, lead).replace("\t", "  ") + fixed.substring(lead);
            }
            if (!YamlText.isComment(fixed) && !YamlText.isBlank(fixed) && YamlText.keyColon(fixed) < 0) {
                Matcher m = MISSING_SPACE.matcher(fixed);
                if (m.matches() && known.contains(m.group(2).trim())) {
                    fixed = m.group(1) + m.group(2) + ": " + m.group(3);
                }
            }
            if (!fixed.equals(line)) {
                work.set(i, fixed);
                act(Action.Kind.FIXED, i, keyOf(fixed), null);
            }
        }
        if (parses(String.join("\n", work))) {
            return work;
        }
        for (YamlDoc.Entry e : YamlDoc.parse(work).entries()) {
            String value = YamlText.scalarValue(work.get(e.keyLine()));
            if (value == null || parses("k: " + value)) {
                continue;
            }
            String repaired = null;
            for (String candidate : scalarCandidates(value)) {
                if (parses("k: " + candidate)) {
                    repaired = candidate;
                    break;
                }
            }
            if (repaired == null) {
                invalid.add(e.keyLine());
                continue;
            }
            work.set(e.keyLine(), YamlText.replaceScalarValue(work.get(e.keyLine()), repaired));
            act(Action.Kind.FIXED, e.keyLine(), e.dotted(), null);
        }
        return work;
    }

    private static List<String> scalarCandidates(String value) {
        List<String> out = new ArrayList<>();
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        boolean quoted = first == '"' || first == '\'';
        if (quoted && (value.length() == 1 || last != first)) {
            out.add(value + first);
        }
        if (first == '"' && last == '"' && value.length() >= 2) {
            out.add('"' + escapeDouble(value.substring(1, value.length() - 1).replace("\\\"", "\"")) + '"');
        }
        if (first == '\'' && last == '\'' && value.length() >= 2) {
            String inner = value.substring(1, value.length() - 1).replace("''", "'");
            out.add('\'' + inner.replace("'", "''") + '\'');
        }
        out.add('"' + escapeDouble(value) + '"');
        return out;
    }

    private static String escapeDouble(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String keyOf(String line) {
        String name = YamlText.keyName(line);
        return name == null ? line.trim() : name;
    }

    private boolean live(YamlDoc.Entry e) {
        return fate[e.index()] == Fate.LIVE;
    }

    private boolean livePath(String path) {
        for (YamlDoc.Entry e : ours.entries()) {
            if (e.path().equals(path) && live(e)) {
                return true;
            }
        }
        return false;
    }

    private void retire(YamlDoc.Entry root, Fate f) {
        for (YamlDoc.Entry e : ours.entries()) {
            if (e.keyLine() >= root.keyLine() && e.keyLine() < root.subtreeEnd()) {
                fate[e.index()] = f;
            }
        }
    }

    private void commentOut(int from, int to, int indent, String marker) {
        List<String> block = new ArrayList<>();
        block.add(YamlText.spaces(indent) + "# " + marker);
        for (int i = from; i < to; i++) {
            String line = ours.lines().get(i);
            block.add(line.trim().isEmpty() ? line : YamlText.spaces(YamlText.indentOf(line)) + "# " + line.trim());
        }
        expectedComments.addAll(block);
        edits.replace(from, block);
        for (int i = from + 1; i < to; i++) {
            edits.replace(i, List.of());
        }
    }

    private void commentDuplicates() {
        Map<String, YamlDoc.Entry> last = new HashMap<>();
        for (YamlDoc.Entry e : ours.entries()) {
            last.put(e.path(), e);
        }
        for (YamlDoc.Entry e : ours.entries()) {
            if (!live(e) || last.get(e.path()) == e) {
                continue;
            }
            commentOut(e.keyLine(), e.subtreeEnd(), e.indent(), markers.duplicate());
            retire(e, Fate.COMMENTED);
            act(Action.Kind.DUPLICATE, e.keyLine(), e.dotted(), null);
        }
    }

    private YamlDoc.Entry liveOurs(String path) {
        YamlDoc.Entry found = null;
        for (YamlDoc.Entry e : ours.entries()) {
            if (e.path().equals(path) && live(e)) {
                found = e;
            }
        }
        return found;
    }

    private void flag(YamlDoc.Entry e, int from, int to, String reason) {
        String pad = YamlText.spaces(e.indent());
        edits.insertBefore(from, List.of(pad + marker(markers.flagStart())));
        edits.insertAfter(to - 1, List.of(pad + marker(reason)));
        retire(e, Fate.FLAGGED);
        claimed.add(e.path());
        act(Action.Kind.FLAGGED, e.keyLine(), e.dotted(), reason);
    }

    private String expectation(ValueShape shape) {
        switch (shape) {
            case BOOLEAN:
                return markers.expectsBoolean();
            case NUMBER:
                return markers.expectsNumber();
            case LIST:
            case BLOCK_LIST:
                return markers.expectsList();
            case BRACES:
                return markers.expectsBraces();
            default:
                return markers.expectsText();
        }
    }

    private boolean underClaimed(String path) {
        return insertedUnder(claimed, path);
    }

    private void flagKindChanges() {
        for (YamlDoc.Entry e : ours.entries()) {
            if (!live(e)) {
                continue;
            }
            YamlDoc.Entry t = theirs.get(e.path());
            if (t == null || t.isParent() == e.isParent()) {
                continue;
            }
            if (untouched(e)) {
                if (mode == Mode.FULL) {
                    edits.delete(e.keyLine(), e.subtreeEnd());
                    retire(e, Fate.DELETED);
                } else {
                    retire(e, Fate.FLAGGED);
                }
                continue;
            }
            flag(e, e.keyLine(), e.subtreeEnd(), t.isParent() ? markers.expectsSection() : markers.expectsValue());
        }
    }

    private boolean untouched(YamlDoc.Entry e) {
        YamlDoc.Entry b = base == null ? null : base.get(e.path());
        if (b == null || b.isParent() != e.isParent()) {
            return false;
        }
        return subtreeText(ours, e).equals(subtreeText(base, b));
    }

    private static String subtreeText(YamlDoc doc, YamlDoc.Entry e) {
        StringBuilder sb = new StringBuilder();
        for (int i = e.keyLine(); i < e.subtreeEnd(); i++) {
            String t = doc.lines().get(i).trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                sb.append(t).append('\n');
            }
        }
        return sb.toString();
    }

    private void flagMisplaced() {
        Map<String, List<YamlDoc.Entry>> leavesByName = new HashMap<>();
        for (YamlDoc.Entry t : theirs.entries()) {
            if (!t.isParent()) {
                leavesByName.computeIfAbsent(t.name(), k -> new ArrayList<>()).add(t);
            }
        }
        for (YamlDoc.Entry e : ours.entries()) {
            if (!live(e) || theirs.has(e.path()) || (base != null && base.has(e.path()))) {
                continue;
            }
            String parentPath = e.parentPath();
            if (parentPath.isEmpty() || theirs.has(parentPath)) {
                YamlDoc.Entry match = closestSibling(e, parentPath);
                if (match != null && match.isParent() == e.isParent()) {
                    claimed.add(match.path());
                    flag(e, e.keyLine(), e.subtreeEnd(), markers.misspelt().replace("%name%", match.name()));
                    continue;
                }
            }
            if (e.isParent()) {
                continue;
            }
            List<YamlDoc.Entry> named = leavesByName.get(e.name());
            if (named == null || named.size() != 1) {
                continue;
            }
            YamlDoc.Entry target = named.get(0);
            if (livePath(target.path()) || underClaimed(target.path())) {
                continue;
            }
            claimed.add(target.path());
            String parent = target.parentPath();
            flag(e, e.keyLine(), e.valueEnd(), parent.isEmpty()
                    ? markers.misplacedTop()
                    : markers.misplaced().replace("%parent%", parent.replace(YamlDoc.SEP, '.')));
        }
    }

    private YamlDoc.Entry closestSibling(YamlDoc.Entry e, String parentPath) {
        int limit = Math.max(1, Math.min(2, e.name().length() / 4));
        YamlDoc.Entry best = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean tie = false;
        for (YamlDoc.Entry candidate : theirs.children(parentPath)) {
            if (livePath(candidate.path()) || underClaimed(candidate.path())) {
                continue;
            }
            int d = distance(e.name(), candidate.name());
            if (d > limit) {
                continue;
            }
            if (d < bestDistance) {
                best = candidate;
                bestDistance = d;
                tie = false;
            } else if (d == bestDistance) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }

    private void resolveUnknown() {
        List<YamlDoc.Entry> parents = new ArrayList<>();
        for (YamlDoc.Entry e : ours.entries()) {
            if (!live(e) || theirs.has(e.path())) {
                continue;
            }
            if (e.isParent()) {
                parents.add(e);
                continue;
            }
            YamlDoc.Entry b = base == null ? null : base.get(e.path());
            if (b != null && !b.isParent() && ours.normalisedValue(e).equals(base.normalisedValue(b))) {
                deleteEntry(e, b, e.valueEnd());
                act(Action.Kind.REMOVED, e.keyLine(), e.dotted(), null);
                continue;
            }
            commentOut(e.keyLine(), e.valueEnd(), e.indent(), markers.unknown());
            fate[e.index()] = Fate.COMMENTED;
            act(Action.Kind.QUARANTINED, e.keyLine(), e.dotted(), null);
        }
        for (int k = parents.size() - 1; k >= 0; k--) {
            YamlDoc.Entry e = parents.get(k);
            YamlDoc.Entry b = base == null ? null : base.get(e.path());
            boolean childrenGone = true;
            for (YamlDoc.Entry c : ours.entries()) {
                if (c.parent() == e.index() && fate[c.index()] != Fate.DELETED) {
                    childrenGone = false;
                    break;
                }
            }
            if (b != null && b.isParent() && childrenGone) {
                deleteEntry(e, b, e.keyLine() + 1);
                act(Action.Kind.REMOVED, e.keyLine(), e.dotted(), null);
                continue;
            }
            commentOut(e.keyLine(), e.keyLine() + 1, e.indent(), markers.unknown());
            fate[e.index()] = Fate.COMMENTED;
            act(Action.Kind.QUARANTINED, e.keyLine(), e.dotted(), null);
        }
    }

    private void deleteEntry(YamlDoc.Entry e, YamlDoc.Entry b, int end) {
        fate[e.index()] = Fate.DELETED;
        boolean regionUntouched = YamlDoc.normaliseRegion(ours.region(e))
                .equals(YamlDoc.normaliseRegion(base.region(b)));
        edits.delete(regionUntouched ? e.regionStart() : e.keyLine(), end);
    }

    private void insertMissing() {
        Set<String> inserted = new HashSet<>();
        for (YamlDoc.Entry t : theirs.entries()) {
            if (theirs.get(t.path()) != t || livePath(t.path())
                    || insertedUnder(inserted, t.path()) || underClaimed(t.path())) {
                continue;
            }
            String parentPath = t.parentPath();
            if (!parentPath.isEmpty() && !livePath(parentPath)) {
                continue;
            }
            int at;
            int delta;
            YamlDoc.Entry prev = previousLiveSibling(t);
            if (prev != null) {
                YamlDoc.Entry o = liveOurs(prev.path());
                at = o.subtreeEnd();
                delta = o.indent() - prev.indent();
            } else if (!parentPath.isEmpty()) {
                YamlDoc.Entry o = liveOurs(parentPath);
                YamlDoc.Entry tp = theirs.get(parentPath);
                at = o.keyLine() + 1;
                delta = o.indent() - tp.indent();
            } else {
                YamlDoc.Entry next = nextLiveSibling(t);
                at = next == null ? ours.eofRegionStart() : liveOurs(next.path()).regionStart();
                delta = 0;
            }
            List<String> shifted = new ArrayList<>();
            for (String line : theirs.lines().subList(t.regionStart(), t.subtreeEnd())) {
                shifted.add(YamlText.reindent(line, delta));
            }
            edits.insertBefore(at, shifted);
            inserted.add(t.path());
        }
    }

    private static boolean insertedUnder(Set<String> paths, String path) {
        for (String p : paths) {
            if (path.equals(p) || path.startsWith(p + YamlDoc.SEP)) {
                return true;
            }
        }
        return false;
    }

    private YamlDoc.Entry previousLiveSibling(YamlDoc.Entry t) {
        YamlDoc.Entry found = null;
        for (YamlDoc.Entry s : theirs.children(t.parentPath())) {
            if (s == t) {
                break;
            }
            if (livePath(s.path())) {
                found = s;
            }
        }
        return found;
    }

    private YamlDoc.Entry nextLiveSibling(YamlDoc.Entry t) {
        boolean after = false;
        for (YamlDoc.Entry s : theirs.children(t.parentPath())) {
            if (s == t) {
                after = true;
                continue;
            }
            if (after && livePath(s.path())) {
                return s;
            }
        }
        return null;
    }

    private List<String> commentedOriginal(YamlDoc.Entry o, int indent) {
        List<String> out = new ArrayList<>();
        String pad = YamlText.spaces(indent);
        out.add(pad + "# " + markers.replaced());
        for (int i = o.keyLine(); i < o.valueEnd(); i++) {
            String line = ours.lines().get(i);
            if (line.trim().isEmpty()) {
                continue;
            }
            int rel = Math.max(0, YamlText.indentOf(line) - o.indent());
            out.add(pad + "# " + YamlText.spaces(rel) + line.trim());
        }
        expectedComments.addAll(out);
        return out;
    }

    private static String coerce(String bundled, ValueShape bundledShape, String owner) {
        if (owner == null || bundled == null) {
            return null;
        }
        if (ValueShape.of(owner) != ValueShape.QUOTED) {
            return null;
        }
        String inner = YamlText.unquote(owner).trim();
        if (bundledShape == ValueShape.NUMBER && ValueShape.isNumber(inner)) {
            return inner;
        }
        if (bundledShape == ValueShape.BOOLEAN && ValueShape.isBooleanWord(inner)) {
            return inner;
        }
        return null;
    }

    private void updateRegions() {
        for (YamlDoc.Entry o : ours.entries()) {
            if (!live(o)) {
                continue;
            }
            YamlDoc.Entry t = theirs.get(o.path());
            if (t == null || liveOurs(o.path()) != o) {
                continue;
            }
            YamlDoc.Entry b = base == null ? null : base.get(o.path());
            List<String> region = ours.region(o);
            if (shouldTakeTheirs(region, theirs.region(t), b == null ? null : base.region(b))) {
                edits.delete(o.regionStart(), o.keyLine());
                List<String> replacement = new ArrayList<>();
                for (String line : theirs.region(t)) {
                    replacement.add(YamlText.reindent(line, o.indent() - t.indent()));
                }
                edits.insertBefore(o.keyLine(), replacement);
            }
        }
        List<String> eof = ours.eofRegion();
        if (shouldTakeTheirs(eof, theirs.eofRegion(), base == null ? null : base.eofRegion())) {
            edits.delete(ours.eofRegionStart(), ours.lines().size());
            edits.insertBefore(ours.lines().size(), theirs.eofRegion());
        }
    }

    private boolean shouldTakeTheirs(List<String> own, List<String> bundled, List<String> previous) {
        String o = YamlDoc.normaliseRegion(own);
        if (o.equals(YamlDoc.normaliseRegion(bundled))) {
            return false;
        }
        if (base == null) {
            return true;
        }
        return previous != null && o.equals(YamlDoc.normaliseRegion(previous));
    }

    private void mergeValues() {
        for (YamlDoc.Entry o : ours.entries()) {
            if (!live(o) || o.isParent()) {
                continue;
            }
            YamlDoc.Entry t = theirs.get(o.path());
            if (invalid.contains(o.keyLine())) {
                if (t == null || t.isParent()) {
                    commentOut(o.keyLine(), o.valueEnd(), o.indent(), markers.replaced());
                    fate[o.index()] = Fate.COMMENTED;
                } else {
                    replaceWithTheirs(o, t, true);
                }
                act(Action.Kind.INVALID, o.keyLine(), o.dotted(), null);
                continue;
            }
            String ov = ours.normalisedValue(o);
            if (t == null || t.isParent()) {
                expectedValues.put(o.path(), ov);
                continue;
            }
            String tv = theirs.normalisedValue(t);
            YamlDoc.Entry b = base == null ? null : base.get(o.path());
            if (!ov.equals(tv)) {
                if (mode == Mode.FULL && b != null && !b.isParent() && ov.equals(base.normalisedValue(b))) {
                    replaceWithTheirs(o, t, false);
                    continue;
                }
                ValueShape ts = theirs.shape(t);
                if (!ts.accepts(ours.shape(o))) {
                    String coerced = coerce(theirs.scalar(t), ts, ours.scalar(o));
                    if (coerced != null) {
                        edits.replace(o.keyLine(), List.of(YamlText.replaceScalarValue(ours.lines().get(o.keyLine()), coerced)));
                        expectedValues.put(o.path(), coerced);
                        act(Action.Kind.COERCED, o.keyLine(), o.dotted(), null);
                    } else {
                        expectedValues.put(o.path(), ov);
                        flag(o, o.keyLine(), o.valueEnd(), expectation(ts));
                    }
                    continue;
                }
            }
            expectedValues.put(o.path(), ov);
            if (mode != Mode.FULL) {
                continue;
            }
            String own = YamlText.trailingComment(ours.lines().get(o.keyLine()));
            String bundled = YamlText.trailingComment(theirs.lines().get(t.keyLine()));
            String previous = b == null ? null : YamlText.trailingComment(base.lines().get(b.keyLine()));
            if (!own.trim().equals(bundled.trim()) && (base == null || (previous != null && own.trim().equals(previous.trim())))) {
                edits.replace(o.keyLine(), List.of(YamlText.withTrailingComment(ours.lines().get(o.keyLine()), bundled)));
            }
        }
    }

    private void replaceWithTheirs(YamlDoc.Entry o, YamlDoc.Entry t, boolean keepOriginal) {
        if (keepOriginal) {
            edits.insertBefore(o.keyLine(), commentedOriginal(o, o.indent()));
        }
        String own = ours.lines().get(o.keyLine());
        String bundled = theirs.lines().get(t.keyLine());
        List<String> replacement = new ArrayList<>();
        replacement.add(own.substring(0, YamlText.keyColon(own) + 1) + bundled.substring(YamlText.keyColon(bundled) + 1));
        for (String line : theirs.valueLines(t)) {
            replacement.add(YamlText.reindent(line, o.indent() - t.indent()));
        }
        edits.replace(o.keyLine(), replacement);
        for (int i = o.keyLine() + 1; i < o.valueEnd(); i++) {
            edits.replace(i, List.of());
        }
        expectedValues.put(o.path(), theirs.normalisedValue(t));
    }

    private String verify(List<String> out) {
        YamlDoc result = YamlDoc.parse(out);
        if (mode == Mode.FULL) {
            for (YamlDoc.Entry t : theirs.entries()) {
                if (!result.has(t.path()) && !underClaimed(t.path())) {
                    return "missing " + t.dotted();
                }
            }
        }
        for (Map.Entry<String, String> e : expectedValues.entrySet()) {
            YamlDoc.Entry last = null;
            for (YamlDoc.Entry candidate : result.entries()) {
                if (candidate.path().equals(e.getKey())) {
                    last = candidate;
                }
            }
            if (last == null || last.isParent() || !result.normalisedValue(last).equals(e.getValue())) {
                return "value of " + e.getKey().replace(YamlDoc.SEP, '.');
            }
        }
        Set<String> present = new HashSet<>(out);
        for (String comment : expectedComments) {
            if (!present.contains(comment)) {
                return "lost " + comment.trim();
            }
        }
        if (!parses(String.join("\n", out))) {
            return "syntax";
        }
        return null;
    }

    private static final class Edits {
        private final List<String> source;
        private final List<List<String>> replaced;
        private final List<List<String>> before;
        private final List<List<String>> after;

        Edits(List<String> source) {
            this.source = source;
            this.replaced = new ArrayList<>();
            this.before = new ArrayList<>();
            this.after = new ArrayList<>();
            for (int i = 0; i <= source.size(); i++) {
                replaced.add(null);
                before.add(new ArrayList<>());
                after.add(new ArrayList<>());
            }
        }

        void replace(int line, List<String> lines) {
            replaced.set(line, new ArrayList<>(lines));
        }

        void delete(int from, int to) {
            for (int i = from; i < to; i++) {
                replaced.set(i, new ArrayList<>());
            }
        }

        void insertBefore(int line, List<String> lines) {
            before.get(line).addAll(lines);
        }

        void insertAfter(int line, List<String> lines) {
            after.get(line).addAll(lines);
        }

        List<String> apply() {
            List<String> out = new ArrayList<>();
            for (int i = 0; i <= source.size(); i++) {
                out.addAll(before.get(i));
                if (i < source.size()) {
                    List<String> r = replaced.get(i);
                    if (r == null) {
                        out.add(source.get(i));
                    } else {
                        out.addAll(r);
                    }
                    out.addAll(after.get(i));
                }
            }
            return out;
        }
    }
}
