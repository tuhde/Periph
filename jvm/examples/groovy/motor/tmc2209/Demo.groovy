///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-groovy:1.3.3

// TMC2209 demo: sensorless homing of a linear axis with StallGuard4 and CoolStep.
// Wiring: MCU TX -> 1 kOhm -> PDN_UART, MCU RX -> PDN_UART directly (echo handling is on).

import it.uhde.periph.connection.UARTConnection
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.chips.motor.TMC2209Full

def enPin = new SysfsOutputPin(17)                                  // create ENN pin, (gpio=17) → OutputPin

def connection = new UARTConnection(System.getenv("UART_PORT") ?: "/dev/ttyS0", 115200, 8, 1.0d, (char) 'N', 100, -1)  // open UART, 115200 8N1, (port, baudRate, dataBits, stopBits, parity, timeoutMs, dePinNum) → UARTConnection
try {
    def motor = new TMC2209Full(connection, 0.11, 0, enPin, true, null, null, null, false, 12_000_000L)  // create Full driver, (connection, rSenseOhm=0.11 Ω, address=0, enPin, echo, stepPin, dirPin, diagPin, multiNode, fclkHz) → TMC2209Full
    int microsteps = 16

    // --- Bring up the link and the power stage ---
    // IOIN.VERSION and IFCNT prove the UART wiring and echo handling work
    // before any motion is attempted.
    def ioin = motor.readIoin()                                                 // read IOIN, () → Ioin
    println("version 0x" + Integer.toHexString(ioin.version) + ", IFCNT " + motor.readIfcnt())  // readIfcnt() → int
    motor.setCurrent(0.8)                                                        // set run current, (rmsA A RMS) → void
    motor.setMicrosteps(microsteps)                                              // set microstep resolution, (n 1–256) → void
    motor.enable()                                                               // power stage on, () → void

    // --- Arm StallGuard4 for the homing speed ---
    // StallGuard needs StealthChop and TCOOLTHRS >= TSTEP > TPWMTHRS; TCOOLTHRS
    // is set just below the TSTEP of the homing speed so detection is active.
    motor.setStallThreshold(60)   // set StallGuard4 threshold, (sgthrs 0–255) → void
    motor.setCoolstepThreshold(12_000_000L / (200 * 256 / 2))   // set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → void

    // --- Drive toward the end stop ---
    // The first 0.5 s is spin-up, where SG_RESULT is not yet valid; afterwards
    // the demo polls SG_RESULT every 100 ms until it drops to 2 * SGTHRS.
    motor.run(200.0)   // spin with the internal generator, (stepsPerS full steps/s) → void
    Thread.sleep(500)
    int sg
    while (true) {
        sg = motor.readStallguard()   // read StallGuard4 result, () → int 0–510
        println("SG_RESULT " + sg)
        if (sg <= 2 * 60) break
        def fault = motor.readFault()   // read fault bitmask, () → int
        if (fault != 0) {
            motor.stop()   // stop the motor, () → void
            motor.disable()   // power stage off, () → void
            println("fault 0x" + Integer.toHexString(fault))
            System.exit(1)
        }
        Thread.sleep(100)
    }

    // --- End stop found: stop and back off ---
    // Reversing for 2 s moves the axis clear of the stop.
    motor.stop()   // stop the motor, () → void
    println("stalled at SG_RESULT " + sg)
    motor.run(-200.0)   // spin with the internal generator, (stepsPerS full steps/s) → void
    Thread.sleep(2000)
    motor.stop()   // stop the motor, () → void

    // --- Final move with CoolStep ---
    // CoolStep lowers the current while the load is light; CS_ACTUAL shows it.
    motor.configureCoolstep(5, 2, 1, 1, 0)   // configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → void
    motor.run(200.0)   // spin with the internal generator, (stepsPerS full steps/s) → void
    10.times {
        Thread.sleep(500)
        println("CS_ACTUAL " + motor.readCurrentScale())   // read actual current scale, () → int 0–31
    }
    motor.stop()   // stop the motor, () → void

    // --- Shut down and report ---
    // Temperature comparators and GSTAT show whether the run stressed the driver.
    println("temperature flags " + motor.readTemperatureFlags())                   // read temperature flags, () → int 0–15
    println("global status " + motor.readGlobalStatus(false))                      // read GSTAT, (clear) → GlobalStatus
    motor.disable()                                                              // power stage off, () → void
} finally {
    connection.close()
}
