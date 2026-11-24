///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-java:1.5.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Ms5607Full;

public class Ms5607Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x77").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Ms5607Full(connection);

            sensor.init();
            checkTrue("prom_crc_ok", sensor.promCrcOk());
            double[] v = sensor.read();
            checkTrue("read_temperature_range", v[0] > -40 && v[0] < 85);
            checkTrue("read_pressure_range", v[1] > 30000 && v[1] < 120000);
            double p = sensor.readPressure();
            checkTrue("read_pressure_single", p > 30000 && p < 120000);
            double t = sensor.readTemperature();
            checkTrue("read_temperature_single", t > -40 && t < 85);
            int[] raw = sensor.readRaw();
            checkTrue("read_raw_range", raw[0] > 0 && raw[1] > 0);
            checkTrue("compensate_matches_read", Math.abs(sensor.compensate(raw[0], raw[1])[1] - v[1]) < 200);
            int[] c = sensor.coefficients();
            checkTrue("coefficients_nonzero", c[0] > 0 && c[5] > 0);
            checkTrue("serial_code_range", sensor.serialCode() >= 0 && sensor.serialCode() < 4096);
            checkTrue("read_prom_length", sensor.readProm().length == 8);
            for (int osr : new int[]{256, 512, 1024, 2048, 4096}) {
                sensor.setOversampling(osr);
                checkTrue("osr_" + osr + "_roundtrip", sensor.getOversampling() == osr);
                double q = sensor.readPressure();
                checkTrue("osr_" + osr + "_read", q > 30000 && q < 120000);
            }
            sensor.startConversion(Ms5607Full.TEMPERATURE);
            Thread.sleep(12);
            checkTrue("non_blocking_adc", sensor.readAdc() > 0);
            double alt = sensor.altitude();
            checkTrue("altitude_plausible", alt > -500 && alt < 6000);
            sensor.reset();
            checkTrue("read_after_reset", sensor.readPressure() > 30000);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        if (failed > 0) System.exit(1);
    }
}
