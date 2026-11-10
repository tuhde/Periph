///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-groovy:1.3.3

// TMC2208 demo: silent desk-fan / camera-slider controller using the internal step generator.
// Wiring: MCU TX -> 1 kOhm -> PDN_UART, MCU RX -> PDN_UART directly (echo handling is on).

import it.uhde.periph.connection.UARTConnection
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.chips.motor.TMC2208Full

def enPin = new SysfsOutputPin(17)                                  // create ENN pin, (gpio=17) → OutputPin

def connection = new UARTConnection(System.getenv("UART_PORT") ?: "/dev/ttyS0", 115200, 8, 1.0d, (char) 'N', 100, -1)  // open UART, 115200 8N1, (port, baudRate, dataBits, stopBits, parity, timeoutMs, dePinNum) → UARTConnection
try {
    def motor = new TMC2208Full(connection, 0.11, enPin, true, null, null, 12_000_000L, false)  // create Full driver, (connection, rSenseOhm=0.11 Ω, enPin, echo, stepPin, dirPin, fclkHz, tmc2224) → TMC2208Full
    int microsteps = 16
    int spreadAbove = 400

    // --- Bring up the link and the power stage ---
    // IOIN.VERSION and IFCNT prove the UART wiring and echo handling work
    // before any motion is attempted.
    def ioin = motor.readIoin()                                                 // read IOIN, () → Ioin
    println("version 0x" + Integer.toHexString(ioin.version) + ", IFCNT " + motor.readIfcnt())  // readIfcnt() → int
    motor.setCurrent(0.8)                                                        // set run current, (rmsA A RMS) → void
    motor.setMicrosteps(microsteps)                                              // set microstep resolution, (n 1–256) → void
    motor.enable()                                                               // power stage on, () → void

    // --- Quiet at low speed, strong at high speed ---
    // StealthChop is silent but loses torque at speed; TPWMTHRS makes the chip
    // change to SpreadCycle once TSTEP drops below the value for 400 steps/s.
    motor.setStealthchopThreshold(12_000_000L / (spreadAbove * 256))             // set StealthChop velocity limit, (tpwmthrs 20 bit) → void

    // --- Ramp up, hold, ramp down in 100 steps/s increments, then reverse ---
    // VACTUAL has no acceleration of its own, so the demo steps the speed and
    // reports whether the chip is in StealthChop or SpreadCycle. Any driver fault
    // stops the motor, disables the driver and exits.
    for (int sign : [1, -1]) {
        for (int phase = 0; phase < 2; phase++) {
            for (int speed : (phase == 0 ? [0, 100, 200, 300, 400, 500, 600] : [600, 500, 400, 300, 200, 100, 0])) {
                motor.run(sign * (double) speed)                                 // spin with the internal generator, (stepsPerS full steps/s) → void
                Thread.sleep(500)
                def status = motor.readStatus()                                 // read driver status, () → Status
                println(speed + " steps/s " + (status.stealth ? "stealth" : "spread") + " CS=" + status.csActual)
                def fault = motor.readFault()                                   // read fault bitmask, () → int
                if (fault != 0) {
                    motor.stop()                                                 // stop the motor, () → void
                    motor.disable()                                              // power stage off, () → void
                    println("fault 0x" + Integer.toHexString(fault))
                    System.exit(1)
                }
            }
            if (phase == 0) Thread.sleep(3000)
        }
        motor.stop()                                                             // stop the motor, () → void
        Thread.sleep(1000)
    }

    // --- Shut down and report ---
    // Temperature comparators and GSTAT show whether the run stressed the driver.
    println("temperature flags " + motor.readTemperatureFlags())                   // read temperature flags, () → int 0–15
    println("global status " + motor.readGlobalStatus(false))                      // read GSTAT, (clear) → GlobalStatus
    motor.disable()                                                              // power stage off, () → void
} finally {
    connection.close()
}
