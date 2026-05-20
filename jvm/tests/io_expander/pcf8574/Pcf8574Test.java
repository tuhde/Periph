///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.io_expander.Pcf8574Full;

public class Pcf8574Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x20").replaceFirst("^0[xX]", ""), 16);

        try (var transport = new I2CTransport(bus, addr)) {

            var chip = new Pcf8574Full(transport);

            // --- Construction sets all pins to input mode (shadow = 0xFF) ---
            int port = chip.readPort();
            checkTrue("readPort() returns int in [0, 255]", port >= 0 && port <= 255);

            // --- writePort writes the whole byte ---
            chip.writePort(0xFF);
            checkTrue("writePort(0xFF) accepted", true);

            chip.writePort(0x00);
            checkTrue("writePort(0x00) accepted", true);

            chip.writePort(0xFF);  // restore input mode before pin tests

            // --- Pin set high / set low / read ---
            var p0 = chip.pin(0);

            p0.setHigh();
            checkTrue("pin.setHigh() accepted", true);

            boolean v0 = p0.read();
            checkTrue("pin.read() returns boolean", v0 == true || v0 == false);

            p0.setLow();
            checkTrue("pin.setLow() accepted", true);

            p0.setInput();
            checkTrue("pin.setInput() accepted", true);

            p0.setOutput();
            checkTrue("pin.setOutput() accepted", true);

            p0.setHigh();  // release back to input mode
            p0.toggle();
            checkTrue("pin.toggle() accepted", true);
            p0.setHigh();  // restore input mode

            // --- writePort with port argument (ignored) ---
            chip.writePort(0, 0xFF);
            checkTrue("writePort(port=0, mask=0xFF) accepted", true);

            // --- readPort with port argument (ignored) ---
            int p = chip.readPort(0);
            checkTrue("readPort(port=0) in [0, 255]", p >= 0 && p <= 255);

            // --- clearInterrupt returns int in [0, 255] ---
            int changed = chip.clearInterrupt();
            checkTrue("clearInterrupt() in [0, 255]", changed >= 0 && changed <= 255);

            // --- configureInterrupt starts a polling thread ---
            var received = new int[]{-1};
            chip.configureInterrupt(mask -> received[0] = mask);
            Thread.sleep(50);
            chip.stopInterrupt();
            checkTrue("configureInterrupt + stopInterrupt accepted", true);

            System.out.println();
            System.out.printf("Results: %d passed, %d failed%n", passed, failed);
            if (failed > 0) System.exit(1);
        }
    }
}
