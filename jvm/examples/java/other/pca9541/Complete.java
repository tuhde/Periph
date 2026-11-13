///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.other.PCA9541Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        try (var connection = new I2CConnection(bus, PCA9541Full.DEFAULT_ADDRESS)) {               // open I²C bus, device address, (bus, address=0x70) → I2CConnection
            var selector = new PCA9541Full(connection);                                            // construct driver, (connection) → PCA9541Full
            System.out.println(selector.getControl());                                             // Read the CONTROL byte, () → int
            System.out.println(selector.status().hasControl);                                      // Decode CONTROL, () → Status
            var all = selector.readAll();                                                          // Read IE, CONTROL, ISTAT, () → {ie, control, istat}
            // one auto-increment read; clears the ISTAT flags
            System.out.println("IE/CONTROL/ISTAT " + java.util.Arrays.toString(all));

            System.out.println("acquire " + selector.acquire(false, 20));                          // Take the downstream bus, (busInit, timeoutMs) → boolean
            // reads CONTROL, writes MYBUS/BUSON per the datasheet rule
            System.out.println("recover " + selector.recoverBus(20));                              // Recover and take the bus, (timeoutMs) → boolean
            // nine clocks, NACK and STOP on the downstream bus, then connect
            System.out.println("control " + selector.hasControl());                                // Report control, () → boolean
            System.out.println("connected " + selector.isConnected());                             // Report the connection, () → boolean
            // BUSON != NBUSON: some master is connected
            selector.disconnect();                                                                 // Isolate the downstream bus, () → void
            // BUSON = NBUSON; ownership bits are left
            selector.connect();                                                                    // Reconnect without changing ownership, () → void

            selector.setInterruptMask(false, true, false, false);                                  // Write IE, (busLost, busNotIdle, busInit, intIn) → void
            // true = masked, no interrupt
            System.out.println(selector.getInterruptMask());                                       // Read IE, () → int
            selector.disableInterrupt(PCA9541Full.SOURCE_INT_IN);                                  // Mask one source, (source) → void
            selector.enableInterrupt(PCA9541Full.SOURCE_INT_IN);                                   // Unmask one source, (source) → void

            selector.onInterrupt(status -> System.out.println("interrupt " + status));             // Subscribe, (callback(status)) → void
            // falling edge on this master's INT, or a 100 ms polling thread without a pin
            selector.testInterrupt(true, false);                                                   // Drive this master's INT low, (own, other) → void
            // the bus bits are written back unchanged
            Thread.sleep(200);
            selector.testInterrupt(false, false);                                                  // Clear the test, (own, other) → void
            System.out.println(selector.pollInterrupt());                                          // Read and clear ISTAT, () → int
            selector.offInterrupt();                                                               // Unsubscribe, () → void
            selector.release();                                                                    // Give the bus to the other master, () → void
        }
    }
}
