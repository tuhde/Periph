///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-kotlin:1.2.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.motor.DRV8830Full
import it.uhde.periph.chips.motor.DRV8830Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, DRV8830Minimal.DEFAULT_ADDRESS).use { connection ->  // open I²C bus, device address, (bus, address=0x60) → I2CConnection
        val motor = DRV8830Full(connection)                                 // construct driver and confirm presence, (connection) → DRV8830Full
                                                                            // one CONTROL read; the chip has no identity register

        motor.drive(2.5)                                                    // Drive at regulated voltage, (voltage V, + = forward) → Unit
                                                                            // maps 2.5 V to the nearest VSET code and sets IN1=1, IN2=0
        Thread.sleep(1000)
        val out = motor.readOutput()                                        // Read back CONTROL, () → Output(voltage V, direction)
                                                                            // decodes VSET to volts and IN1/IN2 to a Direction
        println("commanded %.2f V %s".format(out.voltage, out.direction))

        motor.drive(-1.5)                                                   // Drive at regulated voltage, (voltage V, - = reverse) → Unit
                                                                            // a negative voltage sets IN1=0, IN2=1
        Thread.sleep(1000)

        motor.setOutput(37, true, false)                                    // Write raw CONTROL fields, (vset 6–63, in1, in2) → Unit
                                                                            // VSET 37 is ~2.97 V forward; codes 0–5 throw IllegalArgumentException
        Thread.sleep(1000)

        motor.brake()                                                       // Short-brake, () → Unit
                                                                            // IN1=IN2=1 drives both outputs high
        Thread.sleep(500)
        motor.stop()                                                        // Coast to standby, () → Unit
                                                                            // IN1=IN2=0 leaves both outputs high-impedance

        val fault = motor.readFault()                                       // Read fault status, () → Fault(fault, ocp, uvlo, ots, ilimit)
                                                                            // does not clear — latched OCP/ILIMIT keep the bridge off
        println(fault)
        motor.clearFault()                                                  // Clear fault bits, () → Unit
                                                                            // writes CLEAR=1; re-enables a latched-off bridge

        motor.onInterrupt({ f -> println("fault interrupt $f") })          // Subscribe to FAULTn, (callback, intPin=connection.intPin()) → Unit
                                                                            // falls back to a 5 ms polling thread when no intPin is wired
        val status = motor.pollInterrupt()                                  // Poll fault status, () → Fault
                                                                            // same as readFault(); never clears implicitly
        motor.offInterrupt()                                                // Unsubscribe, () → Unit
        println("poll $status")
    }
}
