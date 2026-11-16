package fixture;

/** The fixture's main class: bootJar needs one to name in its manifest. It is never run. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        System.out.println("fixture");
    }
}
