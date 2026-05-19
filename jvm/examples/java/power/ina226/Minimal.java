///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import com.pi4j.Pi4J;
import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.power.Ina226Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        var pi4j = Pi4J.newAutoContext();                                   // initialise Pi4J, () → Context
        try (var transport = new I2CTransport(pi4j, 1, 0x40)) {            // open I²C bus 1, device 0x40, (bus, address) → I2CTransport
            var ina = new Ina226Minimal(transport, 0.1, 2.0);              // construct driver, (transport, rShunt=0.1 Ω, maxCurrent=2.0 A) → Ina226Minimal

            for (int i = 0; i < 10; i++) {
                double v  = ina.voltage();       // read bus voltage, () → double V
                double vs = ina.shuntVoltage();  // read shunt voltage, () → double V
                double c  = ina.current();       // read current, () → double A
                double p  = ina.power();         // read power, () → double W
                System.out.printf("V=%.3f V  Vshunt=%.6f V  I=%.4f A  P=%.4f W%n", v, vs, c, p);
                Thread.sleep(1000);
            }
        } finally {
            pi4j.shutdown();
        }
    }
}
