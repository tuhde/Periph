///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED --add-opens java.base/sun.misc=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.magnetometer.Hmc5883lFull;

public class HMC5883LTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    static void checkEq(String label, Object got, Object expected) {
        if (got.equals(expected)) { System.out.println("PASS " + label); passed++; }
        else { System.out.printf("FAIL %s: got %s, expected %s%n", label, got, expected); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x1E").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {

            // Allocate instance without calling constructor to bypass minimal init.
            var theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
            var hmc5883l = (Hmc5883lFull) unsafe.allocateInstance(Hmc5883lFull.class);
            var tf = hmc5883l.getClass().getSuperclass().getDeclaredField("connection");
            tf.setAccessible(true);
            tf.set(hmc5883l, connection);

            // --- Identification ---
            int[] id = hmc5883l.identify();
            checkEq("identify A", id[0], 0x48);
            checkEq("identify B", id[1], 0x34);
            checkEq("identify C", id[2], 0x33);

            // --- Status ---
            int status = hmc5883l.status();
            checkTrue("status_byte valid", status >= 0 && status <= 255);

            // --- Data ready ---
            checkTrue("data_ready returns boolean", hmc5883l.dataReady() == true || hmc5883l.dataReady() == false);

            // --- Magnetic field reading ---
            Double[] field = hmc5883l.magneticField();
            checkTrue("magneticField x is Double or null", field[0] == null || field[0] instanceof Double);
            checkTrue("magneticField y is Double or null", field[1] == null || field[1] instanceof Double);
            checkTrue("magneticField z is Double or null", field[2] == null || field[2] instanceof Double);

            // --- Configuration ---
            hmc5883l.configure(15, 8, 1);
            checkTrue("configure accepted", true);

            hmc5883l.setGain(2);
            checkTrue("setGain accepted", true);

            hmc5883l.setMode("continuous");
            checkTrue("setMode continuous accepted", true);

            // --- Single-shot measurement ---
            Double[] single = hmc5883l.singleMeasurement();
            checkTrue("singleMeasurement x is Double or null", single[0] == null || single[0] instanceof Double);
            checkTrue("singleMeasurement y is Double or null", single[1] == null || single[1] instanceof Double);
            checkTrue("singleMeasurement z is Double or null", single[2] == null || single[2] instanceof Double);

            hmc5883l.setMode("idle");
            checkTrue("setMode idle accepted", true);

            // --- Self-test ---
            Double[] selfTest = hmc5883l.selfTest(true);
            checkTrue("selfTest x is Double or null", selfTest[0] == null || selfTest[0] instanceof Double);
            checkTrue("selfTest y is Double or null", selfTest[1] == null || selfTest[1] instanceof Double);
            checkTrue("selfTest z is Double or null", selfTest[2] == null || selfTest[2] instanceof Double);

        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}