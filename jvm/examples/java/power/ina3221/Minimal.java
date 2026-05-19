///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import com.pi4j.Pi4J;
import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.power.Ina3221Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        var pi4j = Pi4J.newAutoContext();                                    // initialise Pi4J, () → Context
        try (var transport = new I2CTransport(pi4j, 1, 0x40)) {            // open I²C bus 1, device 0x40, (bus, address) → I2CTransport
            var ina = new Ina3221Minimal(transport);                        // construct driver (0.1 Ω shunt all channels), (transport) → Ina3221Minimal

            while (true) {
                for (int ch = 1; ch <= 3; ch++) {
                    double v = ina.voltage(ch);                             // read bus voltage, (channel=1–3) → double V
                    double i = ina.current(ch);                             // compute current via shunt, (channel=1–3) → double A
                    double p = ina.power(ch);                               // compute power, (channel=1–3) → double W
                    System.out.printf("CH%d: %.3f V  %.4f A  %.4f W%n", ch, v, i, p);
                }
                System.out.println("---");
                Thread.sleep(1000);
            }
        } finally {
            pi4j.shutdown();
        }
    }
}
