///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.adc_dac.Mcp4728Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x60)) {  // open I²C bus 1, device 0x60, (bus, address) → I2CTransport
            var dac = new Mcp4728Minimal(transport);              // construct driver, (transport) → Mcp4728Minimal

            while (true) {
                dac.setVoltage(0, 0.0);    // set channel A to 0 V, (channel=0–3, fraction=0.0–1.0) → void
                Thread.sleep(1000);
                dac.setVoltage(0, 0.5);    // set channel A to 50% of VDD, (channel=0–3, fraction=0.0–1.0) → void
                Thread.sleep(1000);
                dac.setVoltage(0, 1.0);    // set channel A to VDD, (channel=0–3, fraction=0.0–1.0) → void
                Thread.sleep(1000);
            }
        }
    }
}
