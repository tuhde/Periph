///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.comms.Rfm95Full;

public class Rfm9xTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    static void checkEq(String label, int got, int expected) {
        if (got == expected) { System.out.println("PASS " + label); passed++; }
        else { System.out.println("FAIL " + label + ": got " + got + ", expected " + expected); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus   = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev   = Integer.parseInt(System.getenv().getOrDefault("SPI_DEV", "0"));
        long freq = Long.parseLong(System.getenv().getOrDefault("RFM9X_FREQ", "868000000"));

        try (var connection = new SPIConnection(bus, dev, 0, 5_000_000)) {
            var radio = new Rfm95Full(connection, freq);                             // Create RFM95W driver, (connection, frequencyHz) → Rfm95Full

            checkEq("version", radio.version(), 0x12);

            radio.configure(7, 125.0f, 5, true);
            checkTrue("configure accepted", true);

            radio.setTxPower(17, true);
            checkTrue("set_tx_power accepted", true);

            radio.standby();
            checkTrue("standby accepted", true);

            radio.send("test123".getBytes());
            checkTrue("send accepted", true);

            radio.sleep();
            checkTrue("sleep accepted", true);

            radio.standby();
            checkTrue("wake accepted", true);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
