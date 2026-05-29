///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.gas.Ens160Full;

public class Ens160Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x52"));

        try (var transport = new I2CTransport(bus, addr)) {

            var sensor = new Ens160Full(transport);

            int status = sensor.status();
            checkTrue("status() in range [0, 3]", status >= 0 && status <= 3);

            System.out.println("Waiting for warm-up (may take up to 3 minutes)...");
            boolean warmupOk = false;
            for (int i = 0; i < 240; i++) {
                try { sensor.readAirQuality(); warmupOk = true; break; } catch (Exception e) { Thread.sleep(1000); }
            }
            checkTrue("warmup_complete", warmupOk);

            if (warmupOk) {
                double[] data = sensor.readAirQuality();
                checkTrue("readAirQuality() returns 3 values", data.length == 3);
                checkTrue("aqi in range [1, 5]", data[0] >= 1 && data[0] <= 5);
                checkTrue("tvocPpb >= 0", data[1] >= 0);
                checkTrue("eco2Ppm >= 400", data[2] >= 400);
            }

            sensor.setCompensation(25.0, 50.0);
            checkTrue("setCompensation() accepted", true);

            double tvoc = sensor.readTvoc();
            checkTrue("readTvoc() >= 0", tvoc >= 0);

            double eco2 = sensor.readEco2();
            checkTrue("readEco2() >= 400", eco2 >= 400);

            int aqi = sensor.readAqi();
            checkTrue("readAqi() in range [1, 5]", aqi >= 1 && aqi <= 5);

            double[] actuals = sensor.readCompensationActuals();
            checkTrue("readCompensationActuals() returns 2 values", actuals.length == 2);

            int[] fw = sensor.getFirmwareVersion();
            checkTrue("getFirmwareVersion() returns 3 values", fw.length == 3);

            sensor.sleep();
            checkTrue("sleep() accepted", true);
            Thread.sleep(100);
            sensor.wake();
            checkTrue("wake() accepted", true);

        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
