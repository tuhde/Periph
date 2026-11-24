///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-java:1.5.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.Bh1750Full;

public class Bh1750Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    static boolean inRange(double lux) { return lux >= 0 && lux <= 120000; }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x23").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Bh1750Full(connection);

            sensor.init();
            checkTrue("read_range", inRange(sensor.read()));
            int raw = sensor.readRaw();
            checkTrue("read_raw_range", raw >= 0 && raw <= 65535);
            checkTrue("measure_once_h", inRange(sensor.measureOnce(Bh1750Full.MODE_H)));
            checkTrue("measure_once_h2", inRange(sensor.measureOnce(Bh1750Full.MODE_H2)));
            checkTrue("measure_once_l", inRange(sensor.measureOnce(Bh1750Full.MODE_L)));
            checkTrue("lux_per_count_default", Math.abs(sensor.luxPerCount() - 1 / 1.2) < 1e-9);
            checkTrue("measurement_time_default", Math.abs(sensor.measurementTime() - 0.180) < 1e-9);
            sensor.startContinuous(Bh1750Full.MODE_H);
            Thread.sleep(200);
            checkTrue("read_continuous_range", inRange(sensor.readContinuous()));
            sensor.setMeasurementTime(138);
            checkTrue("mtreg_roundtrip", sensor.getMeasurementTimeRegister() == 138);
            checkTrue("measurement_time_scaled", Math.abs(sensor.measurementTime() - 0.360) < 1e-9);
            checkTrue("read_with_mtreg", inRange(sensor.measureOnce(Bh1750Full.MODE_H)));
            sensor.setSensitivity(1.0);
            checkTrue("sensitivity_default", sensor.getMeasurementTimeRegister() == 69);
            sensor.powerDown();
            sensor.powerOn();
            sensor.reset();
            checkTrue("read_after_reset", inRange(sensor.measureOnce()));
            sensor.powerDown();
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
