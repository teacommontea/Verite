package teacommontea.clisto;

public interface ClistoEvents {

    ClistoEvents NONE = new ClistoEvents() {};

    default void action(Action action) {}

    default void updated(String file) {}

    default void unsafe(String file) {}

    default void replaced(String file, String backup) {}

    default void storeRemoved(String name) {}

    default void movedToStore(String file) {}

    default void moveToStoreFailed(String file, Exception error) {}

    default void renameFailed(String from, String to, Exception error) {}

    default void moveFailed(String from, String to, Exception error) {}

    default void filesChanged(int removed, int rewritten) {}

    default void blocksCarried(String file, int count) {}

    default void blocksAdded(String file, int count) {}
}
