public class TestMain {
    public static void main(String[] args) {
        com.jiezhimod.seal.SealA.verify();
        com.jiezhimod.seal.SealB.verify();
        System.out.println("SEAL OK: no tampering detected");
    }
}
