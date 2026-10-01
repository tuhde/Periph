///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CBus;
import it.uhde.periph.discovery.Discovery;

import java.util.Arrays;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        int[] addresses = Discovery.scan(bus);                            // scan bus, (bus=1) → int[] 7-bit addresses
                                                                          // quick write per address, read byte on 0x30-0x37 / 0x50-0x5F; EBUSY counts as present
        System.out.println(Arrays.toString(addresses));

        try (var i2c = I2CBus.open(bus)) {                                // open bus, (bus=1) → I2CBus
                                                                          // opens /dev/i2c-N once so probes share one file descriptor
            for (int addr = Discovery.FIRST_ADDRESS; addr <= Discovery.LAST_ADDRESS; addr++) {
                var result = i2c.probe(addr, false);                      // probe one address, (address, readByte=false) → I2CBus.Probe
                                                                          // PRESENT, ABSENT, KERNEL_BOUND (EBUSY) or FAILED
                if (result != I2CBus.Probe.ABSENT) System.out.printf("0x%02X %s%n", addr, result);
            }
        }

        for (var d : Discovery.discover(bus, false)) {                    // discover chips, (bus=1, active=false) → List<DiscoveredDevice>
                                                                          // identity reads confirm a chip only when exactly one candidate matches
            System.out.println(d);
        }

        var all = Discovery.discover(bus, true);                          // discover chips incl. write-sensitive addresses, (bus=1, active=true) → List<DiscoveredDevice>
                                                                          // also probes addresses shared with PCF8574/PCF8591/MCP4725-style chips; may change their outputs
        System.out.println(all.size());
    }
}
