///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.discovery.Discovery;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));

        // --- Take inventory of an unknown bench setup ---
        // Scan /dev/i2c-1 and name everything that answers. A chip is only named when
        // its identity register matches exactly one registry entry; shared addresses
        // without an ID register stay as candidate lists.
        var devices = Discovery.discover(bus, false);                     // discover chips, (bus=1, active=false) → List<DiscoveredDevice>

        // --- Report what was found ---
        // identified: confirmed chip; candidates: could be any of these; empty means the
        // address answers but the registry does not know it.
        int skipped = 0;
        for (var d : devices) {
            if (d.identified() != null) System.out.printf("0x%02X  %s (driver: %s)%n", d.address(), d.identified(), d.driver());
            else if (d.inUseByKernel()) System.out.printf("0x%02X  claimed by a kernel driver, could be %s%n", d.address(), d.candidates());
            else if (!d.candidates().isEmpty()) System.out.printf("0x%02X  one of %s%n", d.address(), d.candidates());
            else System.out.printf("0x%02X  unknown device%n", d.address());
            if (d.probeSkippedReason() == Discovery.ProbeSkipReason.WRITE_SENSITIVE_CANDIDATE) skipped++;
        }

        // --- Flag addresses we deliberately did not probe ---
        // Some candidates (port expanders, DACs) treat a stray write as data, so the
        // identity probe is skipped unless Discovery.discover(bus, true) is used.
        System.out.printf("%d address(es) not probed because a candidate is write-sensitive%n", skipped);
    }
}
