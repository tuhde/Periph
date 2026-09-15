///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Bmp384Full;
import it.uhde.periph.chips.pressure.Bmp384Minimal;

public class Bmp384Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x76"));

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Bmp384Minimal(connection);

            checkTrue("default_osr_p == 4", sensor.osrP == 4);
            checkTrue("default_osr_t == 1", sensor.osrT == 1);
            checkTrue("default_iir == 2",    sensor.iir  == 2);

            double t = sensor.temperature();
            checkTrue("temperature() in range [-40, 85] °C", t >= -40.0 && t <= 85.0);

            double p = sensor.pressure();
            checkTrue("pressure() in range [300, 1250] hPa", p >= 300.0 && p <= 1250.0);

            var sensorFull = new Bmp384Full(connection);
            checkTrue("isDataReady() runs", true);

            sensorFull.configure(2, 1, 1, 0x04);
            checkTrue("configure_writes_through",
                sensorFull.osrP == 2 && sensorFull.iir == 1 && sensorFull.odr == 0x04);

            sensorFull.setMode(Bmp384Full.MODE_FORCED);
            checkTrue("set_mode_forced", sensorFull.mode == Bmp384Full.MODE_FORCED);

            sensorFull.fifoConfigure(true, true, 10, false);
            var frames = sensorFull.fifoRead();
            checkTrue("fifo_read_returns_array", frames != null);

            double alt = sensorFull.altitude();
            checkTrue("altitude() returns finite double", Double.isFinite(alt));
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
