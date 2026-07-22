///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.DHTxxTransport;
import it.uhde.periph.chips.humidity.Dht11Minimal;
import it.uhde.periph.chips.humidity.Dht11Full;

public class Dht11Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        // Decode logic tests using in-memory frames
        // Test 1: Decode datasheet example — 53.0%RH, 24.4°C
        Dht11Minimal d1 = new Dht11Minimal(new MockTransport(new byte[]{0x35, 0x00, 0x18, 0x04, 0x51}));
        double[] r1 = d1.read();
        checkTrue("decode_datasheet_example.t", Math.abs(r1[0] - 24.4) < 0.001);
        checkTrue("decode_datasheet_example.h", Math.abs(r1[1] - 53.0) < 0.001);

        // Test 2: Negative temperature
        Dht11Minimal d2 = new Dht11Minimal(new MockTransport(new byte[]{0x20, 0x00, 0x0A, (byte)0x81, (byte)0xAB}));
        double[] r2 = d2.read();
        checkTrue("decode_negative_temperature.t", Math.abs(r2[0] - (-10.1)) < 0.001);
        checkTrue("decode_negative_temperature.h", Math.abs(r2[1] - 32.0) < 0.001);

        // Test 3: Checksum error
        Dht11Minimal d3 = new Dht11Minimal(new MockTransport(new byte[]{0x35, 0x00, 0x18, 0x04, 0x00}));
        boolean raised = false;
        try { d3.read(); } catch (Dht11Minimal.Dht11Exception e) { raised = true; }
        checkTrue("checksum_error_raises", raised);

        // Test 4: read_temperature / read_humidity
        Dht11Full d4 = new Dht11Full(new MockTransport(new byte[]{0x35, 0x00, 0x18, 0x04, 0x51}), 3);
        checkTrue("read_temperature", Math.abs(d4.readTemperature() - 24.4) < 0.001);
        checkTrue("read_humidity",    Math.abs(d4.readHumidity()    - 53.0) < 0.001);

        // Test 5: read_retry success
        FlakyTransport flaky = new FlakyTransport();
        Dht11Full d5 = new Dht11Full(flaky, 3);
        double[] r5 = d5.readRetry(3);
        checkTrue("read_retry_succeeds.t", Math.abs(r5[0] - 24.4) < 0.001);
        checkTrue("read_retry_succeeds.attempts", flaky.attempts == 2);

        // Test 6: read_retry exhausted
        Dht11Full d6 = new Dht11Full(new AlwaysBadTransport(), 2);
        boolean exhausted = false;
        try { d6.readRetry(2); } catch (Dht11Minimal.Dht11Exception e) { exhausted = true; }
        checkTrue("read_retry_exhausted", exhausted);

        // Test 7: readRaw
        Dht11Full d7 = new Dht11Full(new MockTransport(new byte[]{0x35, 0x00, 0x18, 0x04, 0x51}), 3);
        byte[] raw = d7.readRaw();
        checkTrue("read_raw.first_byte", raw[0] == 0x35);
        checkTrue("read_raw.checksum",   raw[4] == 0x51);

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    static class MockTransport extends DHTxxTransport {
        private final byte[] frame;
        MockTransport(byte[] frame) {
            super("/dev/null", 0);
            this.frame = frame;
        }
        @Override
        public byte[] read() {
            return frame.clone();
        }
    }

    static class FlakyTransport extends DHTxxTransport {
        int attempts = 0;
        FlakyTransport() { super("/dev/null", 0); }
        @Override
        public byte[] read() {
            attempts++;
            if (attempts < 2) return new byte[]{0x35, 0x00, 0x18, 0x04, 0x00};
            return new byte[]{0x35, 0x00, 0x18, 0x04, 0x51};
        }
    }

    static class AlwaysBadTransport extends DHTxxTransport {
        AlwaysBadTransport() { super("/dev/null", 0); }
        @Override
        public byte[] read() {
            return new byte[]{0x35, 0x00, 0x18, 0x04, 0x00};
        }
    }
}
