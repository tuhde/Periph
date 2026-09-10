///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Lps33hwFull;

public class Lps33hwTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x5C"));

        try (var connection = new I2CConnection(bus, addr)) {

            var sensor = new Lps33hwFull(connection);

            // status() — STATUS register returns something
            int st = sensor.status();
            checkTrue("status() accepted", true);

            // interruptStatus() — INT_SOURCE register returns something
            int intsrc = sensor.interruptStatus();
            checkTrue("interruptStatus() accepted", true);

            // configure() — must be accepted without exception
            sensor.configure(Lps33hwFull.ODR_10_HZ, true, true,
                             Lps33hwFull.LPFP_BW_ODR_20, false, false);
            checkTrue("configure() accepted", true);

            // oneShot() — must return plausible pressure/temperature
            double[] oneShot = sensor.oneShot();
            checkTrue("oneShot() pressure in [26000, 126000] Pa",
                      oneShot[0] >= 26000.0 && oneShot[0] <= 126000.0);
            checkTrue("oneShot() temperature in [-40, 85] °C",
                      oneShot[1] >= -40.0 && oneShot[1] <= 85.0);

            // pressure() and temperature() — valid ranges
            double p = sensor.pressure();
            checkTrue("pressure() in [26000, 126000] Pa",
                      p >= 26000.0 && p <= 126000.0);
            double t = sensor.temperature();
            checkTrue("temperature() in [-40, 85] °C",
                      t >= -40.0 && t <= 85.0);

            // setPressureOffset() — must be accepted
            sensor.setPressureOffset(0.0);
            checkTrue("setPressureOffset() accepted", true);

            // setAutozero() / clearAutozero() — must be accepted
            sensor.setAutozero();
            sensor.clearAutozero();
            checkTrue("autozero toggle accepted", true);

            // setAutorifp() / clearAutorifp() — must be accepted
            sensor.setAutorifp();
            sensor.clearAutorifp();
            checkTrue("autorifp toggle accepted", true);

            // configureInterrupt() — must be accepted
            sensor.configureInterrupt(true, false, false, false,
                                      Lps33hwFull.INT_S_DATA_SIGNALS,
                                      false, false);
            checkTrue("configureInterrupt() accepted", true);

            // configurePressureInterrupt() — must be accepted
            sensor.configurePressureInterrupt(true, true, 5.0, true);
            checkTrue("configurePressureInterrupt() accepted", true);

            // enableFifo() / disableFifo() / fifoStatus() — must be accepted
            sensor.enableFifo(Lps33hwFull.FIFO_MODE_STREAM, 16);
            int fst = sensor.fifoStatus();
            checkTrue("enableFifo() / fifoStatus() accepted", true);
            sensor.disableFifo();
            checkTrue("disableFifo() accepted", true);

            // resetLpf() — must be accepted
            sensor.resetLpf();
            checkTrue("resetLpf() accepted", true);

            // reset() — must complete and keep sensor functional
            sensor.reset();
            checkTrue("reset() accepted", true);
            double pAfter = sensor.pressure();
            checkTrue("pressure() after reset in [26000, 126000] Pa",
                      pAfter >= 26000.0 && pAfter <= 126000.0);

            // reboot() — must complete without exception
            sensor.reboot();
            checkTrue("reboot() accepted", true);

        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}