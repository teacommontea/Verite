package teacommontea.clisto;

import java.util.List;

public interface BlockFormat {

    boolean startsBlock(String trimmed);

    boolean isSection(String trimmed);

    default String identity(List<String> lines) {
        return BlockMerge.normalise(lines);
    }
}
