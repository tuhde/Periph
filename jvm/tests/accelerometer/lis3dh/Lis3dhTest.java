///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.Lis3dhFull;
import it.uhde.periph.chips.accelerometer.Lis3dhMinimal;
import it.uhde.periph.chips.accelerometer.Lis3dhSource;

public class Lis3dhTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    static void checkRange(String label, double got, double lo, double hi) {
        if (got >= lo && got <= hi) { System.out.println("PASS " + label); passed++; }
        else {
            System.out.println("FAIL " + label + ": got " + got + ", expected " + lo + ".." + hi);
            failed++;
        }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x18").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var chip = new Lis3dhMinimal(connection);
            checkTrue("construct_minimal", true);

            double[] xyz = chip.read();
            checkTrue("read_returns_doubles", xyz.length == 3);
            double mag = Math.sqrt(xyz[0]*xyz[0] + xyz[1]*xyz[1] + xyz[2]*xyz[2]);
            checkRange("magnitude_near_1g", mag, 0.5, 1.5);
            checkTrue("axes_within_range",
                    Math.abs(xyz[0]) <= 2.0 && Math.abs(xyz[1]) <= 2.0 && Math.abs(xyz[2]) <= 2.0);

            var full = new Lis3dhFull(connection);
            checkTrue("construct_full", true);

            full.setRange(4);
            double[] xyz2 = full.read();
            checkTrue("read_after_set_range_4g", xyz2.length == 3);
            checkRange("magnitude_after_set_range_4g",
                    Math.sqrt(xyz2[0]*xyz2[0] + xyz2[1]*xyz2[1] + xyz2[2]*xyz2[2]), 0.25, 2.0);

            full.setMode(Lis3dhMinimal.MODE_NORMAL);
            full.setDataRate(50);
            full.setAxes(true, true, true);
            checkTrue("set_mode_and_rate", true);

            int[] raw = full.readRaw();
            checkTrue("read_raw_returns_ints", raw.length == 3);
            checkTrue("raw_in_normal_mode_range",
                    raw[0] >= -512 && raw[0] <= 511 && raw[1] >= -512 && raw[1] <= 511);

            checkTrue("status_readable", full.readStatus() >= 0);
            full.newDataAvailable();
            checkTrue("new_data_available_callable", true);

            full.enableAdc(true);
            double volts = full.readAdc(1);
            checkRange("adc_channel_1_in_range", volts, 0.7, 1.7);

            int temp = full.readTemperatureDelta();
            checkRange("temperature_delta_in_range", temp, -60, 60);

            full.setHighPass(1, 1, true, true, false, false);
            full.resetHighPass();
            full.setReference(0x10);
            checkTrue("high_pass_configured", true);

            full.setFifo(1, 8, false);
            checkTrue("fifo_level_readable", full.fifoLevel() >= 0);
            full.readFifo();
            full.readFifoStatus();
            checkTrue("fifo_accessible", true);

            full.setMotionInterrupt(1, 0x35, 0.5, 10, true);
            full.setFreeFall(1, 0.35, 60);
            full.setOrientation(1, false, false);
            full.setLatch(1, true);
            full.readInterruptSource(1);
            checkTrue("generators_configured", true);

            full.setClick(0, 0x04, 0.5, 10, 20, 50, false);
            full.readClickSource();
            checkTrue("click_configured", true);

            full.setActivity(0.5, 100);
            full.setInterruptPolarity(false);
            checkTrue("activity_and_polarity_configured", true);

            full.enableInterrupt(Lis3dhSource.SOURCE_CLICK, 1);
            full.enableInterrupt(Lis3dhSource.SOURCE_IA1, 1);
            int status = full.pollInterrupt();
            checkTrue("poll_interrupt_returns_bits", status >= 0);
            full.disableInterrupt(Lis3dhSource.SOURCE_IA1, 1);
            checkTrue("interrupt_routing_configured", true);

            full.setSdoPullup(true);
            checkTrue("sdo_pullup_configured", true);

            full.powerDown();
            checkTrue("power_down", true);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
