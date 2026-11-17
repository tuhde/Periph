///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-java:1.5.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.LM25066Full;

public class Demo {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x40)) {   // open I²C bus 1, device 0x40, (bus, address) → I2CConnection
            var hotswap = new LM25066Full(connection, 1.0, false);   // construct driver, (connection, rSenseMohm, currentLimitHigh) → LM25066Full

            System.out.println(hotswap.manufacturerId() + " " + hotswap.model() + " " + hotswap.revision()); // identify the chip, () → String each
            hotswap.clearFaults();                                                         // release SMBA after power-up, () → void

            // Hot-swap rail monitor: one line per second for a minute
            for (int i = 0; i < 60; i++) {
                if (i == 30) {
                    hotswap.setAveraging(6);                                                           // 64-sample averaging, (exponent) → void
                    hotswap.setVinUvWarn(10.0);                                                        // VIN under-voltage warning at 10 V, (volts) → void
                    hotswap.setIinWarn(20.0);                                                          // input over-current warning at 20 A, (amps) → void
                    hotswap.enableInterrupt(LM25066Full.SOURCE_VIN_UV_WARN | LM25066Full.SOURCE_IIN_WARN); // unmask both alerts, (source) → void
                }
                System.out.printf("VIN %.2f V  VOUT %.2f V  IIN %.3f A (avg %.3f)  PIN %.2f W  T %.1f C%n",
                    hotswap.voltageIn(), hotswap.voltageOut(), hotswap.current(),
                    hotswap.currentAvg(), hotswap.power(), hotswap.temperature());
                Thread.sleep(1000);
            }
            System.out.println("peak power " + hotswap.powerPeak());                                  // peak input power, () → double W
            System.out.println("diagnostic " + hotswap.pollInterrupt());                              // alert flags, () → int
            hotswap.clearFaults();                                                         // finish servicing, () → void
            if (java.util.Arrays.asList(args).contains("--control")) {
                hotswap.operationOff();   // CUTS THE LOAD RAIL, () → void
                Thread.sleep(1000);
                hotswap.operationOn();    // switch the MOSFET back on, () → void
            }
        }
    }
}
