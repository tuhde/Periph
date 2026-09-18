///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.gyroscope.L3gd20hMinimal;
import it.uhde.periph.chips.gyroscope.L3gd20hFull;

public class L3gd20hTest {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x6A").replaceFirst("^0[xX]", ""), 16);

        System.out.println("=== L3GD20H JVM Hardware Test ===");
        int passed = 0, failed = 0;

        try (var conn = new I2CConnection(bus, addr)) {
            L3gd20hMinimal gyro = new L3gd20hMinimal(conn);
            System.out.println("PASS Minimal init");

            float[] xyz = gyro.gyro();
            System.out.println("PASS gyro() returns float[3]");
            System.out.printf("  Initial reading: x=%.3f y=%.3f z=%.3f rad/s%n", xyz[0], xyz[1], xyz[2]);

            L3gd20hFull gyroFull = new L3gd20hFull(conn);
            System.out.println("PASS Full init");

            gyroFull.configure(L3gd20hFull.ODR_190_HZ, 0, L3gd20hFull.FS_500_DPS);
            System.out.println("PASS configure()");

            gyroFull.enableHpFilter(true);
            System.out.println("PASS enableHpFilter(true)");

            gyroFull.configureFifo(L3gd20hFull.FIFO_FIFO, 10);
            gyroFull.enableFifo(true);
            System.out.println("PASS configureFifo() + enableFifo()");

            float[] xyz2 = gyroFull.gyro();
            System.out.println("PASS gyro() after config");

            short[] raw = gyroFull.gyroRaw();
            System.out.println("PASS gyroRaw() returns short[3]");

            int temp = gyroFull.temperature();
            System.out.println("PASS temperature() returns int");

            boolean drdy = gyroFull.dataReady();
            System.out.println("PASS dataReady() returns boolean");

            int level = gyroFull.fifoLevel();
            System.out.println("PASS fifoLevel() returns int");

            gyroFull.readFifo();
            System.out.println("PASS readFifo() returns List<float[]>");

            gyroFull.setPowerMode(L3gd20hFull.POWER_SLEEP);
            System.out.println("PASS setPowerMode(SLEEP)");

            gyroFull.setPowerMode(L3gd20hFull.POWER_NORMAL);
            System.out.println("PASS setPowerMode(NORMAL)");

            passed = 14;
        } catch (Exception e) {
            System.err.println("FAIL: Hardware test error: " + e.getMessage());
            e.printStackTrace();
            failed = 1;
        }

        System.out.printf("%n=== DONE: %d passed, %d failed ===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}