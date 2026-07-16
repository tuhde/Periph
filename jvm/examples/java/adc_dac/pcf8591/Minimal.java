///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.adc_dac.Pcf8591Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x48)) {   // open I²C bus 1, device 0x48, (bus, address) → I2CTransport
            var adc = new Pcf8591Minimal(transport);               // construct driver, (transport) → Pcf8591Minimal

            while (true) {
                int ch0 = adc.readChannel(0);                          // read single channel, (channel=0–3) → int
                int[] all = adc.readAll();                             // read all four channels, () → int[4]
                System.out.printf("ch0=%d all=%d %d %d %d%n", ch0, all[0], all[1], all[2], all[3]);
                Thread.sleep(1000);
            }
        }
    }
}
