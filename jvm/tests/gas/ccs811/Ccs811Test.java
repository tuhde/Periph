///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.gas.Ccs811Full;

public class Ccs811Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    static boolean eco2Ok(int v) {
        return v >= 400 && v <= 8192;
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x5A").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Ccs811Full(connection);
            sensor.init();
            int[] hw = sensor.hardwareVersion();
            checkTrue("hardware_id", hw[0] == 0x81);
            checkTrue("hardware_version", (hw[1] & 0xF0) == 0x10);
            checkTrue("firmware_version", sensor.firmwareVersion().app().major() >= 1);
            int st = sensor.status();
            checkTrue("status_app_mode", (st & Ccs811Full.STATUS_FW_MODE) != 0);
            checkTrue("status_no_error", (st & Ccs811Full.STATUS_ERROR) == 0);

            var m = sensor.read();
            checkTrue("read_eco2_range", eco2Ok(m.eco2Ppm()));
            checkTrue("read_tvoc_range", m.tvocPpb() >= 0 && m.tvocPpb() <= 1187);
            checkTrue("read_eco2_single", eco2Ok(sensor.readEco2()));
            int tvoc = sensor.readTvoc();
            checkTrue("read_tvoc_single", tvoc >= 0 && tvoc <= 1187);
            double[] raw = sensor.readRaw();
            checkTrue("read_raw_current", raw[0] >= 0 && raw[0] <= 63);
            checkTrue("read_raw_voltage", raw[1] >= 0 && raw[1] <= 1.65);
            checkTrue("data_ready_cleared", !sensor.dataReady());
            sensor.setEnvironment(50, 25);
            sensor.setThresholds(1000, 2000, 50);
            int[] ntc = sensor.readNtc();
            checkTrue("read_ntc", ntc[0] >= 0 && ntc[1] >= 0);
            sensor.setBaseline(sensor.getBaseline());
            checkTrue("baseline_roundtrip", sensor.error() == 0);

            sensor.enableInterrupt(Ccs811Full.SOURCE_DATA_READY);
            Thread.sleep(1200);
            checkTrue("poll_interrupt_data_ready", (sensor.pollInterrupt() & Ccs811Full.STATUS_DATA_READY) != 0);
            sensor.read();
            sensor.disableInterrupt(Ccs811Full.SOURCE_DATA_READY);
            sensor.setMode(4);
            Thread.sleep(600);
            checkTrue("mode_4_raw", sensor.readRaw()[0] <= 63);
            sensor.setMode(0);
            sensor.setMode(1);
            sensor.softReset();
            checkTrue("status_after_reset", (sensor.status() & Ccs811Full.STATUS_FW_MODE) != 0);
            checkTrue("read_after_reset", eco2Ok(sensor.read().eco2Ppm()));
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
