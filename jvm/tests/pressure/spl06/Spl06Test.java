///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Spl06Full;

public class Spl06Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    static boolean inRange(double p) {
        return p > 30000 && p < 110000;
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x77").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Spl06Full(connection);

            sensor.init();
            var co = sensor.coefficients();
            checkTrue("coefficients_decoded", co.c0() != 0 || co.c1() != 0 || co.c00() != 0 || co.c10() != 0);
            var m = sensor.read();
            checkTrue("read_pressure_range", inRange(m.pressurePa()));
            checkTrue("read_temperature_range", m.temperatureC() > -40 && m.temperatureC() < 85);
            checkTrue("read_pressure_single", inRange(sensor.readPressure()));
            double t = sensor.readTemperature();
            checkTrue("read_temperature_single", t > -40 && t < 85);
            int[] raw = sensor.readRaw();
            checkTrue("compensate_raw", inRange(sensor.compensate(raw[0], raw[1], 16, 1).pressurePa()));
            double alt = sensor.altitude();
            checkTrue("altitude_range", alt > -500 && alt < 9000);
            for (int osr : new int[]{1, 8, 128}) {
                sensor.setPressureConfig(osr, 1);
                checkTrue("read_at_osr_" + osr, inRange(sensor.readPressure()));
            }
            sensor.setPressureConfig(16, 8);
            sensor.setTemperatureConfig(1, 8);
            sensor.startContinuous();
            checkTrue("read_continuous", inRange(sensor.readContinuous().pressurePa()));
            Thread.sleep(300);
            checkTrue("data_ready", sensor.dataReady()[0]);
            sensor.stop();
            sensor.flushFifo();
            sensor.enableFifo();
            sensor.startContinuous();
            Thread.sleep(1000);
            sensor.stop();
            checkTrue("fifo_not_empty", !sensor.fifoStatus()[0]);
            var entries = sensor.readFifo();
            checkTrue("fifo_read", entries.size() >= 4);
            checkTrue("fifo_values", entries.stream()
                    .filter(e -> e.kind().equals(Spl06Full.KIND_PRESSURE)).allMatch(e -> inRange(e.value())));
            checkTrue("fifo_empty_after_read", sensor.fifoStatus()[0]);
            sensor.disableFifo();
            sensor.enableInterrupt(Spl06Full.SOURCE_PRESSURE);
            sensor.pollInterrupt();
            sensor.read();
            checkTrue("poll_interrupt_pressure", (sensor.pollInterrupt() & Spl06Full.SOURCE_PRESSURE) != 0);
            sensor.disableInterrupt(Spl06Full.SOURCE_PRESSURE);
            sensor.reset();
            checkTrue("read_after_reset", inRange(sensor.readPressure()));
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
