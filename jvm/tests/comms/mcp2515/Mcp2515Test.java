///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.chips.comms.Mcp2515Full;
import it.uhde.periph.chips.comms.Mcp2515Minimal;
import it.uhde.periph.connection.SPIConnection;

public class Mcp2515Test {

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

    static void checkEq(String label, String got, String expected) {
        if (got != null && got.equals(expected)) { System.out.println("PASS " + label); passed++; }
        else { System.out.println("FAIL " + label + ": got " + got + ", expected " + expected); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev = Integer.parseInt(System.getenv().getOrDefault("SPI_DEV", "0"));

        try (var connection = new SPIConnection(bus, dev)) {
            var can = new Mcp2515Full(connection);                                  // Create MCP2515 driver, (connection, bitrateKbps=125, oscMhz=8) → Mcp2515Full

            checkEq("init mode", can.getMode(), "normal");

            can.setMode(Mcp2515Full.MODE_LOOPBACK);                                  // enter Loopback mode, (mode="loopback") → void
            checkEq("loopback mode", can.getMode(), "loopback");

            // --- send + recv round trip via internal loopback ---
            can.send(0x123, new byte[] { 0x01, 0x02, 0x03, 0x04 });                  // send standard frame, (id=0x123, data=4 bytes, extended=false) → int
            Mcp2515Minimal.CanFrame rx = can.recv(500);                             // receive one frame, (timeoutMs=500) → CanFrame | null
            checkTrue("loopback receive", rx != null);
            if (rx != null) {
                checkEq("loopback id", rx.id, 0x123);
                checkEq("loopback dlc", rx.data.length, 4);
                checkTrue("loopback not extended", !rx.extended);
            }

            can.send(0x456, new byte[] { 0xAA, 0xBB }, false, 1);                    // send via TXB1, (id=0x456, data=2 B, extended=false, buf=1) → int
            rx = can.recv(500);                                                      // receive one frame, (timeoutMs=500) → CanFrame | null
            checkTrue("sendBuffered receive", rx != null);
            if (rx != null) {
                checkEq("sendBuffered id", rx.id, 0x456);
                checkEq("sendBuffered dlc", rx.data.length, 2);
            }

            can.send(0x1FFFFFFF, new byte[] { 0xCC }, true);                        // send extended, (id=0x1FFFFFFF, data=1 B, extended=true) → int
            rx = can.recv(500);                                                      // receive one frame, (timeoutMs=500) → CanFrame | null
            checkTrue("extended receive", rx != null);
            if (rx != null) {
                checkEq("extended id", rx.id, 0x1FFFFFFF);
                checkTrue("extended flag set", rx.extended);
            }

            // --- Draining mode-switches back to normal ---
            can.setMode(Mcp2515Full.MODE_NORMAL);                                    // enter Normal mode, (mode="normal") → void
            checkEq("back to normal", can.getMode(), "normal");

            // --- read errors: tec/rec should be 0 on a healthy bus/loopback ---
            Mcp2515Full.Errors errs = can.readErrors();                              // read error counters, () → Errors
            checkEq("tec", errs.tec, 0);
            checkEq("rec", errs.rec, 0);

            // --- one-shot toggle ---
            can.setOneShot(true);                                                    // enable one-shot, (enable=true) → void
            can.setOneShot(false);                                                   // disable one-shot, (enable=false) → void
            checkTrue("one-shot toggle accepted", true);

            // --- clear overflow flags (no-op if not set) ---
            can.clearOverflow(Mcp2515Full.RXB0);                                     // clear RX0OVR, (buf=0) → void
            can.clearOverflow(Mcp2515Full.RXB1);                                     // clear RX1OVR, (buf=1) → void
            checkTrue("clear overflow accepted", true);

            // --- filter setup in Config mode ---
            can.setMode(Mcp2515Full.MODE_CONFIG);                                    // enter Config mode, (mode="config") → void
            can.setMask(0, 0x7FF, false);                                            // mask 0, (maskNum=0, mask=0x7FF, extended=false) → void
            can.setFilter(0, 0x123, false);                                          // filter 0, (filterNum=0, id=0x123, extended=false) → void
            can.setFilter(1, 0x456, false);                                          // filter 1, (filterNum=1, id=0x456, extended=false) → void
            can.setMask(1, 0x7FF, false);                                            // mask 1, (maskNum=1, mask=0x7FF, extended=false) → void
            can.setRxMode(Mcp2515Full.RXB0, 0);                                      // filter mode, (buf=0, mode=0) → void
            can.setRxMode(Mcp2515Full.RXB1, 0);                                      // filter mode, (buf=1, mode=0) → void
            checkEq("config mode after filter setup", can.getMode(), "config");
            can.setMode(Mcp2515Full.MODE_NORMAL);                                    // back to normal, (mode="normal") → void
            checkEq("filter restored mode", can.getMode(), "normal");

            // --- abort TX: should complete without error even with no TX in flight ---
            can.abortTx();                                                           // abort all pending TX, () → void
            checkTrue("abortTx accepted", true);

            can.reset();                                                             // SPI RESET, () → void
            Thread.sleep(50);
            checkEq("reset returns to config", can.getMode(), "config");
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
