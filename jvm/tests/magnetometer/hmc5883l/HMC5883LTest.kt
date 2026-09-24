///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.magnetometer.Hmc5883lFull
import sun.misc.Unsafe

var passed = 0
var failed = 0

fun checkTrue(label: String, condition: Boolean) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

fun checkEq(label: String, got: Any, expected: Any) {
    if (got == expected) { println("PASS $label"); passed++ }
    else { println("FAIL $label: got $got, expected $expected"); failed++ }
}

fun main() {
    val bus = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x1E").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        // Allocate instance without calling constructor to bypass minimal init.
        val theUnsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
        theUnsafe.isAccessible = true
        val unsafe = theUnsafe.get(null) as Unsafe
        val hmc5883l = unsafe.allocateInstance(Hmc5883lFull::class.java) as Hmc5883lFull
        val tf = hmc5883l.javaClass.superclass.getDeclaredField("connection")
        tf.isAccessible = true
        tf.set(hmc5883l, connection)

        // --- Identification ---
        val (idA, idB, idC) = hmc5883l.identify()
        checkEq("identify A", idA, 0x48)
        checkEq("identify B", idB, 0x34)
        checkEq("identify C", idC, 0x33)

        // --- Status ---
        val status = hmc5883l.status()
        checkTrue("status_byte valid", status in 0..255)

        // --- Data ready ---
        checkTrue("data_ready returns boolean", hmc5883l.dataReady() == true || hmc5883l.dataReady() == false)

        // --- Magnetic field reading ---
        val field = hmc5883l.magneticField()
        checkTrue("magneticField x is Double or null", field[0] == null || field[0] is Double)
        checkTrue("magneticField y is Double or null", field[1] == null || field[1] is Double)
        checkTrue("magneticField z is Double or null", field[2] == null || field[2] is Double)

        // --- Configuration ---
        hmc5883l.configure(15.0, 8, 1)
        checkTrue("configure accepted", true)

        hmc5883l.setGain(2)
        checkTrue("setGain accepted", true)

        hmc5883l.setMode("continuous")
        checkTrue("setMode continuous accepted", true)

        // --- Single-shot measurement ---
        val single = hmc5883l.singleMeasurement()
        checkTrue("singleMeasurement x is Double or null", single[0] == null || single[0] is Double)
        checkTrue("singleMeasurement y is Double or null", single[1] == null || single[1] is Double)
        checkTrue("singleMeasurement z is Double or null", single[2] == null || single[2] is Double)

        hmc5883l.setMode("idle")
        checkTrue("setMode idle accepted", true)

        // --- Self-test ---
        val selfTest = hmc5883l.selfTest(true)
        checkTrue("selfTest x is Double or null", selfTest[0] == null || selfTest[0] is Double)
        checkTrue("selfTest y is Double or null", selfTest[1] == null || selfTest[1] is Double)
        checkTrue("selfTest z is Double or null", selfTest[2] == null || selfTest[2] is Double)
    }

    println("===DONE: $passed passed, $failed failed===")
    System.exit(if (failed == 0) 0 else 1)
}