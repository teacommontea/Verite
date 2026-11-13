package teacommontea.clisto;

@FunctionalInterface
public interface Syntax {

    void check(String text) throws Exception;
}
