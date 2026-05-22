///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.pressure.Bmp280Full;

public class Bmp280Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x76"), 16);

        try (var transport = new I2CTransport(bus, addr)) {

            var sensor = new Bmp280Full(transport);

            // chipId() — must return 0x58 for BMP280
            int id = sensor.chipId();
            checkTrue("chipId() == 0x58", id == 0x58);

            // temperature() — must be in sensor operating range
            double t = sensor.temperature();
            checkTrue("temperature() in range [-20, 85] °C", t >= -20.0 && t <= 85.0);

            // pressure() — valid range per datasheet
            double p = sensor.pressure();
            checkTrue("pressure() in range [300, 1100] hPa", p >= 300.0 && p <= 1100.0);

            // altitude() — just checks it returns a finite double
            double alt = sensor.altitude();
            checkTrue("altitude() returns finite double", Double.isFinite(alt));

            // seaLevelPressure(0.0) — must yield a positive value
            double slp = sensor.seaLevelPressure(0.0);
            checkTrue("seaLevelPressure(0.0) > 0", slp > 0.0);

            // configure() — must be accepted without exception
            sensor.configure(Bmp280Full.OSRS_X2, Bmp280Full.OSRS_X4,
                             Bmp280Full.MODE_FORCED, Bmp280Full.FILTER_4,
                             Bmp280Full.T_SB_250_MS);
            checkTrue("configure() accepted", true);

            // setOversampling() — must be accepted without exception
            sensor.setOversampling(Bmp280Full.OSRS_X1, Bmp280Full.OSRS_X1);
            checkTrue("setOversampling() accepted", true);

            // setFilter() — must be accepted without exception
            sensor.setFilter(Bmp280Full.FILTER_OFF);
            checkTrue("setFilter(FILTER_OFF) accepted", true);

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
