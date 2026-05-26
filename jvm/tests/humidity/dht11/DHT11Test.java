///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.chips.humidity.DHT11Full;

public class DHT11Test {
    public static void main(String[] args) throws Exception {
        int passed = 0;
        int failed = 0;

        var dht = new DHT11Full(null);

        try {
            byte[] raw = dht.readRaw();
            int sum = (raw[0] + raw[1] + raw[2] + raw[3]) & 0xFF;
            if (sum == (raw[4] & 0xFF)) {
                System.out.println("PASS checksum");
                passed++;
            } else {
                System.out.println("FAIL checksum");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("FAIL checksum: " + e.getMessage());
            failed++;
        }

        try {
            double[] result = dht.read();
            if (result[0] > -40 && result[0] < 80) {
                System.out.println("PASS temperature_range");
                passed++;
            } else {
                System.out.println("FAIL temperature_range");
                failed++;
            }
            if (result[1] >= 0 && result[1] <= 100) {
                System.out.println("PASS humidity_range");
                passed++;
            } else {
                System.out.println("FAIL humidity_range");
                failed++;
            }
        } catch (Exception e) {
            System.out.println("FAIL read: " + e.getMessage());
            failed++;
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }
}
