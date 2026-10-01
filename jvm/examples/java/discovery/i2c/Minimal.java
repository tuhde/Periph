///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.discovery.Discovery;

public class Minimal {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        for (var d : Discovery.discover(bus, false)) {                    // discover chips, (bus=1, active=false) → List<DiscoveredDevice>
            System.out.printf("0x%02X %s%n", d.address(), d.identified() != null ? d.identified() : d.candidates());
        }
    }
}
