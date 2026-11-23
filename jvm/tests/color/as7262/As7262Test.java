///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-java:1.5.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.color.As7262Full;

public class As7262Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x49").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new As7262Full(connection);
            sensor.init();

            checkTrue("device type", sensor.deviceType() == 0x40);
            int[] hw = sensor.hardwareVersion();
            checkTrue("hardware version", hw[0] == 0x40 && hw[1] == 0x3E);
            double t = sensor.temperature();
            checkTrue("temperature range", t >= -40 && t <= 125);

            var d = sensor.read();
            checkTrue("calibrated channels", d.violet >= 0 && d.red >= 0);
            var r = sensor.readRaw();
            checkTrue("raw channels", r.violet >= 0 && r.violet <= 65535);
            int raw2 = sensor.readChannelRaw(2);
            checkTrue("readChannelRaw", raw2 >= 0 && raw2 <= 65535);

            sensor.setGain(64);
            checkTrue("gain", sensor.getGain() == 64.0);
            sensor.setGain(16);
            sensor.setIntegrationTime(50);
            checkTrue("integration time", Math.abs(sensor.getIntegrationTime() - 50.4) < 0.01);
            sensor.setMode(2);
            checkTrue("mode", sensor.getMode() == 2);
            sensor.setMode(3);

            sensor.measure();
            Thread.sleep(600);
            checkTrue("data ready", sensor.dataReady());
            checkTrue("last calibrated", sensor.readCalibratedLast() != null);

            sensor.setIndicatorLed(true, 4);
            sensor.setIndicatorLed(false, 1);
            sensor.softReset();
            checkTrue("reset int_t", sensor.getIntegrationTime() > 700);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
