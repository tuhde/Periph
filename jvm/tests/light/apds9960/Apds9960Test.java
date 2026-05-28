///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.light.Apds9960Full;

public class Apds9960Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x39").replaceFirst("^0[xX]", ""), 16);

        try (var transport = new I2CTransport(bus, addr)) {

            var apds = new Apds9960Full(transport);

            checkTrue("chipId() == 0xAB", apds.chipId() == 0xAB);

            int[] rgbc = apds.color();
            checkTrue("color_clear >= 0", rgbc[0] >= 0);
            checkTrue("color_red >= 0", rgbc[1] >= 0);
            checkTrue("color_green >= 0", rgbc[2] >= 0);
            checkTrue("color_blue >= 0", rgbc[3] >= 0);

            checkTrue("isAlsValid()", apds.isAlsValid());

            apds.enableProximity(true);
            Thread.sleep(100);
            int p = apds.proximity();
            checkTrue("proximity() <= 255", p <= 255);
            checkTrue("isProximityValid()", apds.isProximityValid());

            apds.configureAls(0xB6, 1);
            Thread.sleep(210);
            checkTrue("als valid after configure", apds.isAlsValid());

            apds.alsThreshold(100, 60000);
            apds.proximityThreshold(10, 200);
            apds.setPersistence(0, 1);
            checkTrue("persistence set", true);

            apds.enableAlsInterrupt(true);
            apds.enableProximityInterrupt(true);
            apds.clearAlsInterrupt();
            apds.clearProximityInterrupt();
            apds.clearAllInterrupts();
            checkTrue("interrupts cleared", true);

            apds.setProximityOffset(10, -5);
            apds.setProximityMask(false, false, false, false);
            checkTrue("proximity offset/mask set", true);

            apds.enableGesture(true);
            apds.configureGesture(1, 0, 0, 1, 1, 50, 20);
            checkTrue("gesture configured", true);
            checkTrue("gestureFifoLevel() >= 0", apds.gestureFifoLevel() >= 0);
            apds.clearGestureFifo();
            apds.enableGestureInterrupt(false);
            apds.enableGesture(false);
            checkTrue("gesture disabled", true);

            checkTrue("status() readable", apds.status() >= 0);

            apds.enableProximity(false);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
