///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.gas.Ens160Full

def transport = new I2CTransport(1, 0x52)                // open I²C bus 1, device 0x52, (bus, address=0x52) → I2CTransport
try {
    def sensor = new Ens160Full(transport)                      // construct driver, verifies PART_ID and starts STANDARD mode, (transport) → Ens160Full

    int[] fw = sensor.getFirmwareVersion()                      // get firmware version, () → int[] {major, minor, release}
                                                                 // switches to IDLE, issues GET_APPVER, returns to STANDARD
    printf("Firmware: %d.%d.%d%n", fw[0], fw[1], fw[2])

    sensor.setCompensation(25.0, 50.0)                          // set compensation, (tempCelsius, rhPercent) → void
                                                                 // improves accuracy with external T/RH readings

    sensor.configureInterrupt(true, false, false, true, false)  // configure interrupt, (enabled, activeHigh, pushPull, onData, onGpr) → void
                                                                 // sets INTn pin behavior for new data notification

    println("Waiting for warm-up...")
    while (true) {                                              // Wait for valid data, () → blocks until warm
        try { sensor.readAirQuality(); break } catch (Exception e) { Thread.sleep(1000) }
    }

    double tvoc = sensor.readTvoc()                             // read TVOC, () → double ppb
    double eco2 = sensor.readEco2()                             // read eCO2, () → double ppm
    int aqi = sensor.readAqi()                                  // read AQI, () → int 1–5
    double ethanol = sensor.readEthanol()                       // read ethanol, () → double ppb
                                                                 // alias of DATA_TVOC at 0x22
    double r1 = sensor.readRawResistance(1)                     // read raw resistance, (sensor=1 or 4) → double Ohms
    double r4 = sensor.readRawResistance(4)                     // read raw resistance, (sensor=1 or 4) → double Ohms
    double[] actuals = sensor.readCompensationActuals()         // read compensation actuals, () → double[] {tempCelsius, rhPercent}
                                                                 // returns T/RH values used by sensor

    printf("TVOC=%.0f ppb, eCO2=%.0f ppm, AQI=%d%n", tvoc, eco2, aqi)
    printf("Ethanol=%.0f ppb, R1=%.0f Ohm, R4=%.0f Ohm%n", ethanol, r1, r4)
    printf("Actual T=%.1f C, RH=%.1f %%%n", actuals[0], actuals[1])

    sensor.sleep()                                              // enter deep sleep, () → void
                                                                 // reduces current to ~10 uA
    Thread.sleep(1000)
    sensor.wake()                                               // wake and resume sensing, () → void
                                                                 // transitions IDLE then STANDARD
} finally {
    transport.close()
}
