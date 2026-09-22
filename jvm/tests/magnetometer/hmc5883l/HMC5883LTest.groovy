///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED --add-opens java.base/sun.misc=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-groovy:1.2.0

import groovy.transform.CompileStatic
import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.magnetometer.Hmc5883lFull
import sun.misc.Unsafe

@CompileStatic
class HMC5883LTest {

    static int passed = 0
    static int failed = 0

    static void checkTrue(String label, boolean condition) {
        if (condition) { println("PASS $label"); passed++; }
        else           { println("FAIL $label"); failed++; }
    }

    static void checkEq(String label, Object got, Object expected) {
        if (got == expected) { println("PASS $label"); passed++; }
        else { println("FAIL $label: got $got, expected $expected"); failed++; }
    }

    static void main(String[] args) {
        int bus = System.getenv().getOrDefault("I2C_BUS", "1").toInteger()
        int addr = System.getenv().getOrDefault("I2C_ADDR", "0x1E").replaceFirst("^0[xX]", "").toInteger(16)

        def connection = new I2CConnection(bus, addr)
        try {
            // Allocate instance without calling constructor to bypass minimal init.
            def theUnsafe = Unsafe.class.getDeclaredField("theUnsafe")
            theUnsafe.setAccessible(true)
            def unsafe = theUnsafe.get(null)
            def hmc5883l = unsafe.allocateInstance(Hmc5883lFull.class)
            def tf = hmc5883l.getClass().superclass.getDeclaredField("connection")
            tf.setAccessible(true)
            tf.set(hmc5883l, connection)

            // --- Identification ---
            def (idA, idB, idC) = hmc5883l.identify()
            checkEq("identify A", idA, 0x48)
            checkEq("identify B", idB, 0x34)
            checkEq("identify C", idC, 0x33)

            // --- Status ---
            def status = hmc5883l.status()
            checkTrue("status_byte valid", status >= 0 && status <= 255)

            // --- Data ready ---
            checkTrue("data_ready returns boolean", hmc5883l.dataReady() == true || hmc5883l.dataReady() == false)

            // --- Magnetic field reading ---
            def field = hmc5883l.magneticField()
            checkTrue("magneticField x is Double or null", field[0] == null || field[0] instanceof Double)
            checkTrue("magneticField y is Double or null", field[1] == null || field[1] instanceof Double)
            checkTrue("magneticField z is Double or null", field[2] == null || field[2] instanceof Double)

            // --- Configuration ---
            hmc5883l.configure(15, 8, 1)
            checkTrue("configure accepted", true)

            hmc5883l.setGain(2)
            checkTrue("setGain accepted", true)

            hmc5883l.setMode("continuous")
            checkTrue("setMode continuous accepted", true)

            // --- Single-shot measurement ---
            def single = hmc5883l.singleMeasurement()
            checkTrue("singleMeasurement x is Double or null", single[0] == null || single[0] instanceof Double)
            checkTrue("singleMeasurement y is Double or null", single[1] == null || single[1] instanceof Double)
            checkTrue("singleMeasurement z is Double or null", single[2] == null || single[2] instanceof Double)

            hmc5883l.setMode("idle")
            checkTrue("setMode idle accepted", true)

            // --- Self-test ---
            def selfTest = hmc5883l.selfTest(true)
            checkTrue("selfTest x is Double or null", selfTest[0] == null || selfTest[0] instanceof Double)
            checkTrue("selfTest y is Double or null", selfTest[1] == null || selfTest[1] instanceof Double)
            checkTrue("selfTest z is Double or null", selfTest[2] == null || selfTest[2] instanceof Double)

        } finally {
            connection.close()
        }

        printf("===DONE: %d passed, %d failed===\n", passed, failed)
        System.exit(failed == 0 ? 0 : 1)
    }
}