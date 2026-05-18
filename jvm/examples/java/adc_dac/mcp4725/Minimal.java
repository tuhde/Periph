///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import com.pi4j.Pi4J;
import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.adc_dac.Mcp4725Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        var pi4j = Pi4J.newAutoContext();                           // initialise Pi4J, () → Context
        try (var transport = new I2CTransport(pi4j, 1, 0x60)) {   // open I²C bus 1, device 0x60, (bus, address) → I2CTransport
            var dac = new Mcp4725Minimal(transport);               // construct driver, (transport) → Mcp4725Minimal

            while (true) {
                dac.setVoltage(0.0);   // set output to 0 V, (fraction=0.0–1.0) → void
                Thread.sleep(1000);
                dac.setVoltage(0.5);   // set output to 50% of VDD, (fraction=0.0–1.0) → void
                Thread.sleep(1000);
                dac.setVoltage(1.0);   // set output to VDD, (fraction=0.0–1.0) → void
                Thread.sleep(1000);
            }
        } finally {
            pi4j.shutdown();
        }
    }
}
