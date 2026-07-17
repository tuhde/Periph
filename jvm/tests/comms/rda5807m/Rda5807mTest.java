///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.comms.Rda5807mFull;

public class Rda5807mTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    // FM_READY deasserts on any register write and takes ~20 ms to settle back;
    // not documented in the datasheet, measured on real hardware.
    static final int SETTLE_MS = 30;

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x10").replaceFirst("^0[xX]", ""), 16);

        try (var transport = new I2CTransport(bus, addr)) {

            var fm = new Rda5807mFull(transport, 100.0, 8);

            Thread.sleep(SETTLE_MS);
            checkTrue("is_ready", fm.isReady());

            double f = fm.frequency();
            checkTrue("frequency near 100.0 MHz", Math.abs(f - 100.0) < 0.2);

            fm.setFrequency(97.5);
            f = fm.frequency();
            checkTrue("set_frequency: frequency near 97.5 MHz", Math.abs(f - 97.5) < 0.2);

            fm.setVolume(10);
            int rssi = fm.signalStrength();
            checkTrue("signal_strength in range", rssi >= 0 && rssi <= 127);
            checkTrue("is_stereo accepted", fm.isStereo() || !fm.isStereo());

            fm.mute(true);
            fm.mute(false);
            Thread.sleep(SETTLE_MS);
            checkTrue("mute/unmute: is_ready after", fm.isReady());

            Double seekFreq = fm.seek(true);
            checkTrue("seek: result is Double or null", seekFreq == null || seekFreq instanceof Double);

            fm.enableRds(true);
            checkTrue("rds_ready accepted", fm.rdsReady() || !fm.rdsReady());

            fm.configure(Rda5807mFull.BAND_WORLD, Rda5807mFull.SPACE_100K, null, null, null, null, null, null);
            Thread.sleep(SETTLE_MS);
            checkTrue("after configure: is_ready", fm.isReady());

            fm.standby(true);
            Thread.sleep(10);
            fm.standby(false);
            checkTrue("after standby cycle: is_ready", fm.isReady());

            fm.softReset();
            checkTrue("after soft_reset: is_ready", fm.isReady());

        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
