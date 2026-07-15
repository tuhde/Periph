///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.environmental.Bme280Full;

public class Bme280Test {

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

            var sensor = new Bme280Full(transport);

            int id = sensor.chipId();
            checkTrue("chipId() == 0x60", id == 0x60);

            double t = sensor.temperature();
            checkTrue("temperature in -40..85 °C", t >= -40.0 && t <= 85.0);

            double p = sensor.pressure();
            checkTrue("pressure in 300..1100 hPa", p >= 300.0 && p <= 1100.0);

            double h = sensor.humidity();
            checkTrue("humidity in 0..100 %RH", h >= 0.0 && h <= 100.0);

            sensor.setOversampling(Bme280Full.OSRS_X4, Bme280Full.OSRS_X2, Bme280Full.OSRS_X1);
            checkTrue("setOversampling", true);

            double alt = sensor.altitude(1013.25);
            checkTrue("altitude in -500..9000 m", alt >= -500.0 && alt <= 9000.0);

            double slp = sensor.seaLevelPressure(0.0);
            checkTrue("sea-level pressure in 900..1100 hPa", slp >= 900.0 && slp <= 1100.0);

            double dp = sensor.dewPoint();
            checkTrue("dew point in -100..100 °C", dp >= -100.0 && dp <= 100.0);

            sensor.reset();
            checkTrue("reset", true);

            System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}
