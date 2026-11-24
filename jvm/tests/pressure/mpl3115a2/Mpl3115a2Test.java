///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-java:1.5.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Mpl3115a2Full;

public class Mpl3115a2Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x60").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Mpl3115a2Full(connection);

            sensor.init();
            checkTrue("who_am_i", sensor.whoAmI() == 0xC4);
            double[] m = sensor.read();
            checkTrue("read_pressure_range", m[0] > 20000 && m[0] < 110000);
            checkTrue("read_temperature_range", m[1] > -40 && m[1] < 85);
            double p = sensor.readPressure();
            checkTrue("read_pressure_single", p > 20000 && p < 110000);
            double t = sensor.readTemperature();
            checkTrue("read_temperature_single", t > -40 && t < 85);
            for (int ratio : new int[]{1, 8, 128}) {
                sensor.setOversample(ratio);
                checkTrue("oversample_roundtrip_" + ratio, sensor.getOversample() == ratio);
                double r = sensor.readPressure();
                checkTrue("read_at_osr_" + ratio, r > 20000 && r < 110000);
            }
            sensor.setOversample(16);
            sensor.setSeaLevelPressure(101325.0);
            double alt = sensor.readAltitude();
            checkTrue("read_altitude_range", alt > -500 && alt < 9000);
            sensor.setMode(false);
            sensor.startContinuous(0);
            checkTrue("is_active", sensor.isActive());
            double[] c = sensor.readContinuous();
            checkTrue("read_continuous", c[0] > 20000 && c[0] < 110000);
            sensor.stopContinuous();
            checkTrue("is_standby", !sensor.isActive());
            sensor.fifoSetup(Mpl3115a2Full.FIFO_CIRCULAR, 4);
            sensor.startContinuous(0);
            Thread.sleep(5000);
            checkTrue("fifo_collects", sensor.fifoStatus()[2] >= 2);
            checkTrue("fifo_read_count", sensor.fifoRead().size() >= 2);
            sensor.stopContinuous();
            sensor.fifoSetup(Mpl3115a2Full.FIFO_OFF, 0);
            sensor.enableInterrupt(Mpl3115a2Full.SOURCE_DRDY, 1);
            sensor.disableInterrupt(Mpl3115a2Full.SOURCE_DRDY);
            sensor.reset();
            double r2 = sensor.readPressure();
            checkTrue("read_after_reset", r2 > 20000 && r2 < 110000);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
