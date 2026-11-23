///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-groovy:1.5.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.LM25066Full

def connection = new I2CConnection(1, 0x40)   // open I²C bus 1, device 0x40, (bus, address) → I2CConnection
try {
    def hotswap = new LM25066Full(connection, 1.0d, false)   // construct driver, (connection, rSenseMohm, currentLimitHigh) → LM25066Full

    println(hotswap.manufacturerId() + " " + hotswap.model() + " " + hotswap.revision()) // identify the chip, () → String each
    hotswap.clearFaults()                                                          // release SMBA after power-up, () → void

    // Hot-swap rail monitor: one line per second for a minute
    60.times { int i ->
        if (i == 30) {
            hotswap.setAveraging(6)                                                            // 64-sample averaging, (exponent) → void
            hotswap.setVinUvWarn(10.0d)                                                        // VIN under-voltage warning at 10 V, (volts) → void
            hotswap.setIinWarn(20.0d)                                                          // input over-current warning at 20 A, (amps) → void
            hotswap.enableInterrupt(LM25066Full.SOURCE_VIN_UV_WARN | LM25066Full.SOURCE_IIN_WARN) // unmask both alerts, (source) → void
        }
        printf("VIN %.2f V  VOUT %.2f V  IIN %.3f A (avg %.3f)  PIN %.2f W  T %.1f C%n",
        hotswap.voltageIn(), hotswap.voltageOut(), hotswap.current(),
        hotswap.currentAvg(), hotswap.power(), hotswap.temperature())
        Thread.sleep(1000)
    }
    println("peak power " + hotswap.powerPeak())                                   // peak input power, () → double W
    println("diagnostic " + hotswap.pollInterrupt())                               // alert flags, () → int
    hotswap.clearFaults()                                                          // finish servicing, () → void
    if (args.contains("--control")) {
        hotswap.operationOff()   // CUTS THE LOAD RAIL, () → void
        Thread.sleep(1000)
        hotswap.operationOn()    // switch the MOSFET back on, () → void
    }
} finally {
    connection.close()
}
