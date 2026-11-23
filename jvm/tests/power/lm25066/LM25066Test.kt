///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-kotlin:1.5.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.LM25066Full

var passed = 0
var failed = 0

fun checkTrue(label: String, condition: Boolean) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

fun avg(g: LM25066Full): Boolean { g.setAveraging(4); val ok = g.getAveraging() == 4; g.setAveraging(0); return ok }
fun uv(g: LM25066Full): Boolean { g.setVinUvWarn(8.0); return Math.abs(g.getLimits().vinUvWarn!! - 8.0) < 0.05 }
fun uvOff(g: LM25066Full): Boolean { g.setVinUvWarn(null); return g.getLimits().vinUvWarn == null }
fun ot(g: LM25066Full): Boolean { g.setOtWarn(110.0); val ok = Math.abs(g.getLimits().otWarn!! - 110.0) < 0.1; g.setOtWarn(125.0); return ok }
fun peak(g: LM25066Full): Boolean { g.clearPowerPeak(); g.clearFaults(); return g.powerPeak() >= 0.0 }

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt() ?: 1
    val addr = System.getenv("I2C_ADDR")?.removePrefix("0x")?.removePrefix("0X")?.toInt(16) ?: 0x40

    I2CConnection(bus, addr).use { connection ->
        val hotswap = LM25066Full(connection, 1.0, false)

        checkTrue("model is LM25066", hotswap.model().equals("LM25066"))
        checkTrue("manufacturer id", hotswap.manufacturerId().equals("NSC"))
        checkTrue("vin range", hotswap.voltageIn() >= 0.0 && hotswap.voltageIn() <= 24.0)
        checkTrue("vout range", hotswap.voltageOut() >= 0.0 && hotswap.voltageOut() <= 24.0)
        checkTrue("current readable", hotswap.current() > -1.0)
        checkTrue("power readable", hotswap.power() > -5.0)
        checkTrue("temperature plausible", hotswap.temperature() > -40.0 && hotswap.temperature() < 150.0)
        checkTrue("vaux range", hotswap.voltageAux() >= 0.0 && hotswap.voltageAux() <= 1.2)
        checkTrue("block readable", hotswap.readBlock().power > -5.0)
        checkTrue("avg block readable", hotswap.readAverage().power > -5.0)
        checkTrue("averaging", avg(hotswap))
        checkTrue("vin_uv_warn", uv(hotswap))
        checkTrue("vin_uv_warn disabled", uvOff(hotswap))
        checkTrue("ot_warn", ot(hotswap))
        checkTrue("peak cleared", peak(hotswap))
        checkTrue("status_word readable", hotswap.statusWord() >= 0)
        checkTrue("diagnostic readable", hotswap.diagnosticWord() >= 0)
        checkTrue("capability", hotswap.capability() == 0xB0)

        println("===DONE: $passed passed, $failed failed===")
        System.exit(if (failed == 0) 0 else 1)
    }
}
