package teacommontea.clisto;

public interface ConfigStore {

    byte[] read(String name);

    void write(String name, byte[] bytes);

    boolean remove(String name);
}
