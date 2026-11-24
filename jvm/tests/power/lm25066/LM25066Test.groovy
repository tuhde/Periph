///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-groovy:1.5.4
import groovy.transform.Field
import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.LM25066Full

@Field int passed = 0
@Field int failed = 0

def checkTrue(String label, boolean condition) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

boolean avg(LM25066Full g) { g.setAveraging(4); boolean ok = g.getAveraging() == 4; g.setAveraging(0); ok }
boolean uv(LM25066Full g) { g.setVinUvWarn(8.0d); Math.abs(g.getLimits().vinUvWarn - 8.0d) < 0.05d }
boolean uvOff(LM25066Full g) { g.setVinUvWarn(null); g.getLimits().vinUvWarn == null }
boolean ot(LM25066Full g) { g.setOtWarn(110.0d); boolean ok = Math.abs(g.getLimits().otWarn - 110.0d) < 0.1d; g.setOtWarn(125.0d); ok }
boolean peak(LM25066Full g) { g.clearPowerPeak(); g.clearFaults(); g.powerPeak() >= 0.0d }

int bus  = (System.getenv('I2C_BUS') ?: '1').toInteger()
int addr = Integer.parseInt((System.getenv('I2C_ADDR') ?: '0x40').replaceFirst(/^0[xX]/, ''), 16)

def connection = new I2CConnection(bus, addr)
try {
    def hotswap = new LM25066Full(connection, 1.0d, false)

    checkTrue("model is LM25066", hotswap.model().equals("LM25066"))
    checkTrue("manufacturer id", hotswap.manufacturerId().equals("NSC"))
    checkTrue("vin range", hotswap.voltageIn() >= 0.0d && hotswap.voltageIn() <= 24.0d)
    checkTrue("vout range", hotswap.voltageOut() >= 0.0d && hotswap.voltageOut() <= 24.0d)
    checkTrue("current readable", hotswap.current() > -1.0d)
    checkTrue("power readable", hotswap.power() > -5.0d)
    checkTrue("temperature plausible", hotswap.temperature() > -40.0d && hotswap.temperature() < 150.0d)
    checkTrue("vaux range", hotswap.voltageAux() >= 0.0d && hotswap.voltageAux() <= 1.2d)
    checkTrue("block readable", hotswap.readBlock().power > -5.0d)
    checkTrue("avg block readable", hotswap.readAverage().power > -5.0d)
    checkTrue("averaging", avg(hotswap))
    checkTrue("vin_uv_warn", uv(hotswap))
    checkTrue("vin_uv_warn disabled", uvOff(hotswap))
    checkTrue("ot_warn", ot(hotswap))
    checkTrue("peak cleared", peak(hotswap))
    checkTrue("status_word readable", hotswap.statusWord() >= 0)
    checkTrue("diagnostic readable", hotswap.diagnosticWord() >= 0)
    checkTrue("capability", hotswap.capability() == 0xB0)

    println("===DONE: $passed passed, $failed failed===")
    System.exit(failed == 0 ? 0 : 1)
} finally {
    connection.close()
}
