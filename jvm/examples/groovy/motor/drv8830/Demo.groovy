///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

// Battery-powered toy motor controller: holds a regulated 3.0 V forward, then
// 2.0 V reverse, printing the commanded output every second — the DRV8830
// keeps that average voltage constant as the battery sags. Brakes, then
// coasts. After every drive() the fault register is checked; a fault (e.g. a
// stalled motor tripping ILIMIT) stops the motor and clears it.

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.motor.DRV8830Full

void checkFault(DRV8830Full motor) {
    // --- Recover from a fault instead of leaving the bridge latched off ---
    // OCP and ILIMIT disable the H-bridge until CLEAR is written; stop first
    // so the motor does not lurch back to the old command on clear.
    def f = motor.readFault()                                              // Read fault status, () → Fault
    if (f.fault) {
        println("fault: ocp=${f.ocp} uvlo=${f.uvlo} ots=${f.ots} ilimit=${f.ilimit}")
        motor.stop()                                                       // Coast to standby, () → void
        motor.clearFault()                                                 // Clear fault bits, () → void
    }
}

void run(DRV8830Full motor, double voltage, int seconds) {
    // --- Hold a regulated voltage and watch it stay put ---
    // The chip PWM-regulates the bridge against VCC internally, so the
    // commanded voltage (and motor speed) holds while the battery discharges.
    motor.drive(voltage)                                                   // Drive at regulated voltage, (voltage V, signed) → void
    checkFault(motor)
    seconds.times {
        Thread.sleep(1000)
        def out = motor.readOutput()                                       // Read back CONTROL, () → Output(voltage V, direction)
        println(String.format("%-7s %.2f V", out.direction, out.voltage))
    }
}

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, DRV8830Full.DEFAULT_ADDRESS)       // open I²C bus, device address, (bus, address=0x60) → I2CConnection
try {
    def motor = new DRV8830Full(connection)                                // construct driver and confirm presence, (connection) → DRV8830Full

    run(motor, 3.0d, 5)
    run(motor, -2.0d, 5)

    // --- Stop quickly, then release ---
    // Braking shorts the winding for a fast stop; coasting afterwards removes
    // the load so the motor does not sit shorted indefinitely.
    motor.brake()                                                          // Short-brake, () → void
    Thread.sleep(500)
    motor.stop()                                                           // Coast to standby, () → void
} finally {
    connection.close()
}
