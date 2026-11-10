///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.MCP4XXXFull
import it.uhde.periph.chips.adc_dac.MCP4XXXMinimal

String part = args.length > 0 ? args[0] : 'MCP4532'   // any of MCP4531 ... MCP4662
def connection = new I2CConnection(1, MCP4XXXMinimal.defaultAddress(part))  // open I²C bus 1, device at the part's default address, (bus, address) → I2CConnection
try {
    def pot = new MCP4XXXFull(connection, part, 10000.0d, 75.0d)     // construct driver, (connection, part, rAb=10000 Ω, rWiper=75 Ω, hvc=null, hvcSettleMs=1) → MCP4XXXFull
    pot.init()                                                       // bus check, () → void

    // --- Identify the family member ---
    // One driver covers sixteen parts; the descriptor tells resolution, networks and memory type.
    printf("%s %d network(s) %d steps %s %s%n", part, pot.channels(), pot.steps(),     // part properties, () → int
            pot.isPotentiometer() ? 'pot' : 'rheostat', pot.isNonvolatile() ? 'EEPROM' : 'RAM')
    printf("power-up wiper %d %.1f ohm%n", pot.getWiper(0), pot.getResistance(0))      // read wiper, (channel=0) → int

    // --- Set 2.2 kΩ and report the quantisation error ---
    // The wiper snaps to the nearest step of RAB / max code.
    pot.setResistance(2200.0d, 0)                                    // set W-B resistance, (ohms, channel=0) → void
    double actual = pot.getResistance(0)                             // read W-B resistance, (channel=0) → double Ω
    printf("target=2200 ohm code=%d actual=%.1f ohm error=%.1f ohm%n", pot.getWiper(0), actual, actual - 2200.0d)

    // --- Step the wiper up and down ---
    // increment / decrement send one command byte per step in one transaction and saturate at full scale and zero scale.
    [8, 8, -4, 10].each { int m ->
        int code = m > 0 ? pot.increment(m, 0) : pot.decrement(-m, 0)   // step wiper, (steps=1, channel=0) → int
        printf("%+d -> code=%d  R_WB=%.1f ohm%n", m, code, pot.getResistance(0))
    }

    // --- Ratios ---
    // The ratio is the fraction of RAB between W and B (the unloaded divider ratio of a potentiometer).
    [0.0d, 0.25d, 0.5d, 0.75d, 1.0d].each { double ratio ->
        pot.setRatio(ratio, 0)                                       // set wiper ratio, (ratio 0.0-1.0, channel=0) → void
        String wa = pot.isPotentiometer() ? String.format('  R_WA=%.1f', pot.getResistanceWa(0)) : ''  // read W-A resistance, (channel=0) → double Ω
        printf("ratio=%.2f code=%d R_WB=%.1f%s%n", ratio, pot.getWiper(0), pot.getResistance(0), wa)
    }

    // --- Dual parts: drive both networks ---
    if (pot.channels() == 2) {
        pot.setBoth(32, 96)                                          // set both wipers, (code0, code1) → void
        printf("both: %d %d -> %d %d%n", pot.getWiper(0), pot.getWiper(1), pot.increment(5, 1), pot.decrement(2, 1))
    }

    // --- Shutdown and terminal disconnect ---
    // Shutdown ties W to B and opens A without losing the wiper; setTerminals(w = false) floats W.
    pot.shutdown(0)                                                  // shut network down, (channel=0) → void
    println("shutdown=${pot.isShutdown(0)} code=${pot.getWiper(0)}") // read state, (channel=0) → boolean
    Thread.sleep(1000)
    pot.resume(0)                                                    // leave shutdown, (channel=0) → void
    pot.setTerminals(true, false, true, 0)                           // disconnect the wiper, (a, w, b, channel=0) → void
    println("terminals ${pot.getTerminals(0)}")                      // read terminals, (channel=0) → (a, w, b)
    pot.setTerminals(true, true, true, 0)                            // reconnect all terminals, (a, w, b, channel=0) → void

    // --- Non-volatile parts: EEPROM wiper and data ---
    // storeWiper makes the setting survive power cycles; every EEPROM write uses one of the
    // 1 000 000 write cycles, so do not do this in a loop.
    if (pot.isNonvolatile()) {
        println("busy=${pot.readStatus().eepromBusy}")               // read STATUS, () → Status
        println("stored ${pot.storeWiper(0)}")                       // store the wiper in EEPROM, (channel=0) → int
        pot.writeData(0, 0x155)                                      // write calibration marker, (index 0-9, value 0-0x1FF) → void
        printf("data0 %#x nv wiper %d%n", pot.readData(0), pot.getNvWiper(0))  // read back, (index) → int
    }

    // --- Back to the power-up state ---
    pot.reset()                                                      // emulate power-on reset, () → void
    println("after reset: ${pot.getWiper(0)}")
} finally {
    connection.close()
}
