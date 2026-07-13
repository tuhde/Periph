///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.environmental.Bme680Full;

public class Bme680Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x76"));

        try (var transport = new I2CTransport(bus, addr)) {

            var sensor = new Bme680Full(transport);

            // chip_id — 0x61 = BME680
            int id = sensor.chipId();
            checkTrue("chip_id: chipId() == 0x61", id == 0x61);

            // temperature_range — must be in sensor operating range
            double t = sensor.temperature();
            checkTrue("temperature_range: temperature() in range [-20, 85] °C",
                      t >= -20.0 && t <= 85.0);

            // pressure_range — valid range per datasheet
            double p = sensor.pressure();
            checkTrue("pressure_range: pressure() in range [300, 1100] hPa",
                      p >= 300.0 && p <= 1100.0);

            // humidity_range — valid range per datasheet
            double h = sensor.humidity();
            checkTrue("humidity_range: humidity() in range [0, 100] %RH",
                      h >= 0.0 && h <= 100.0);

            // gas_resistance — must return a positive value
            double g = sensor.gasResistance();
            checkTrue("gas_resistance: gasResistance() > 0", g > 0.0);

            // default_oversampling — configure() with defaults accepted
            sensor.configure(Bme680Full.OSRS_X2, Bme680Full.OSRS_X16,
                             Bme680Full.OSRS_X1, Bme680Full.MODE_FORCED,
                             Bme680Full.FILTER_0);
            checkTrue("default_oversampling: configure() accepted", true);

            // set_oversampling — must be accepted without exception
            sensor.setOversampling(Bme680Full.OSRS_X4, Bme680Full.OSRS_X4,
                                   Bme680Full.OSRS_X4);
            checkTrue("set_oversampling: setOversampling() accepted", true);

            // read_all — returns double[4] with valid ranges
            double[] all = sensor.readAll();
            checkTrue("read_all: array length == 4", all.length == 4);
            checkTrue("read_all: temperature in range [-20, 85] °C",
                      all[0] >= -20.0 && all[0] <= 85.0);
            checkTrue("read_all: pressure in range [300, 1100] hPa",
                      all[1] >= 300.0 && all[1] <= 1100.0);
            checkTrue("read_all: humidity in range [0, 100] %RH",
                      all[2] >= 0.0 && all[2] <= 100.0);

            // reset — must complete without exception and keep sensor functional
            sensor.reset();
            checkTrue("reset: reset() accepted", true);

            double tAfter = sensor.temperature();
            checkTrue("reset: temperature() after reset in range [-20, 85] °C",
                      tAfter >= -20.0 && tAfter <= 85.0);

        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
