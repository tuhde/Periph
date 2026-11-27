///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.Htu31dFull;

public class Htu31dTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Htu31dFull(connection);

            sensor.init();
            double[] v = sensor.read();
            checkTrue("read_temperature_range", v[0] > -40 && v[0] < 125);
            checkTrue("read_humidity_range", v[1] >= 0 && v[1] <= 100);
            double h = sensor.readHumidity();
            checkTrue("read_humidity_single", h >= 0 && h <= 100);
            double t = sensor.readTemperature();
            checkTrue("read_temperature_single", t > -40 && t < 125);
            int[] raw = sensor.readRaw();
            checkTrue("read_raw_range", raw[0] >= 0 && raw[0] <= 65535 && raw[1] >= 0 && raw[1] <= 65535);
            checkTrue("dew_point_below_temperature", sensor.dewPoint() <= t + 1);
            int serial = sensor.serialNumber();
            checkTrue("serial_number_24bit", serial >= 0 && serial <= 0xFFFFFF);
            checkTrue("diagnostic_no_nvm_error", !sensor.diagnosticFlags().get("nvm_err"));
            for (int osr = 0; osr < 4; osr++) {
                sensor.setResolution(osr, osr);
                int[] r = sensor.getResolution();
                checkTrue("resolution_roundtrip_" + osr, r[0] == osr && r[1] == osr);
                double[] m = sensor.read();
                checkTrue("read_at_osr_" + osr, m[1] >= 0 && m[1] <= 100 && m[0] > -40);
            }
            sensor.setResolution(3, 3);
            checkTrue("conversion_time_osr33", Math.abs(sensor.conversionTime() - 0.02032) < 1e-6);
            sensor.startConversion();
            Thread.sleep(30);
            double[] res = sensor.readResult();
            checkTrue("start_conversion_read_result", res[1] >= 0 && res[1] <= 100);
            sensor.setHeater(true);
            checkTrue("heater_on", sensor.isHeaterOn());
            sensor.setHeater(false);
            checkTrue("heater_off", !sensor.isHeaterOn());
            sensor.softReset();
            checkTrue("read_after_reset", sensor.read()[1] >= 0);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
