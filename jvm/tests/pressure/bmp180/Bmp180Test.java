///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.pressure.Bmp180Full;

public class Bmp180Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        // BMP180 has a fixed I²C address of 0x77; I2C_ADDR is intentionally ignored.
        int addr = 0x77;

        try (var transport = new I2CTransport(bus, addr)) {

            var sensor = new Bmp180Full(transport);

            // temperature() — must be in sensor operating range
            double t = sensor.temperature();
            checkTrue("temperature() in range [-20, 85] °C", t >= -20.0 && t <= 85.0);

            // pressure() — valid range per datasheet
            double p = sensor.pressure();
            checkTrue("pressure() in range [300, 1100] hPa", p >= 300.0 && p <= 1100.0);

            // altitude() — just checks it returns a double without throwing
            double alt = sensor.altitude();
            checkTrue("altitude() returns double", Double.isFinite(alt));

            // seaLevelPressure(0.0) — must yield a positive value
            double slp = sensor.seaLevelPressure(0.0);
            checkTrue("seaLevelPressure(0.0) > 0", slp > 0.0);

            // chipId() — must return 0x55
            int id = sensor.chipId();
            checkTrue("chipId() == 0x55", id == 0x55);

            // setOversampling(3) — must be accepted without exception
            sensor.setOversampling(3);
            checkTrue("setOversampling(3) accepted", true);

            // oversampling() — must reflect the value just set
            int oss = sensor.oversampling();
            checkTrue("oversampling() == 3", oss == 3);

            // reset() — must complete without exception and keep sensor functional
            sensor.reset();
            checkTrue("reset() accepted", true);

            // verify sensor still works after reset
            double tAfter = sensor.temperature();
            checkTrue("temperature() after reset in range [-20, 85] °C",
                      tAfter >= -20.0 && tAfter <= 85.0);

        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
