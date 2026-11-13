package teacommontea.veritesauver.util;

import java.util.TreeMap;

public final class SauverProtocol {

    private static final int SNAPSHOT_BIT = 0x40000000;

    private static final TreeMap<Integer, String> TABLE = new TreeMap<>();
    private static final TreeMap<Integer, String> SNAPSHOTS = new TreeMap<>();

    static {

        TABLE.put(757, "1.18.1");
        TABLE.put(758, "1.18.2");

        TABLE.put(759, "1.19");
        TABLE.put(760, "1.19.2");
        TABLE.put(761, "1.19.3");
        TABLE.put(762, "1.19.4");

        TABLE.put(763, "1.20.1");
        TABLE.put(764, "1.20.2");
        TABLE.put(765, "1.20.4");
        TABLE.put(766, "1.20.6");

        TABLE.put(767, "1.21.1");
        TABLE.put(768, "1.21.3");
        TABLE.put(769, "1.21.4");
        TABLE.put(770, "1.21.5");
        TABLE.put(771, "1.21.6");
        TABLE.put(772, "1.21.8");
        TABLE.put(773, "1.21.10");
        TABLE.put(774, "1.21.11");

        TABLE.put(775, "26.1.2");
        TABLE.put(776, "26.2");
        TABLE.put(777, "26.3");

        SNAPSHOTS.put(346, "26.4 Snapshot 8");
    }

    private SauverProtocol() {}

    public static String versionName(int protocol) {
        if (protocol <= 0) {
            return null;
        }
        if ((protocol & SNAPSHOT_BIT) != 0) {
            int number = protocol & ~SNAPSHOT_BIT;
            var snapshot = SNAPSHOTS.floorEntry(number);
            if (snapshot == null) {
                return "snapshot " + number;
            }
            return number > snapshot.getKey() ? snapshot.getValue() + "+" : snapshot.getValue();
        }
        var floor = TABLE.floorEntry(protocol);
        if (floor == null) {
            return "pre-1.20 (legacy)";
        }
        if (protocol > TABLE.lastKey()) {
            return floor.getValue() + "+";
        }
        return floor.getValue();
    }
}
