///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

// TMC2130 demo: sensorless homing of a linear axis with StallGuard2.
// Wiring: SPI Mode 3 (SPI_MODE pin high); STEP/DIR/ENN on GPIO 27/22/17 (DIAG0 on GPIO 23 for the complete example).

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.chips.motor.TMC2130Full

def stepPin = new SysfsOutputPin(27)                                          // create STEP pin, (gpio=27) → OutputPin
def dirPin = new SysfsOutputPin(22)                                           // create DIR pin, (gpio=22) → OutputPin
def enPin = new SysfsOutputPin(17)                                            // create ENN pin, (gpio=17) → OutputPin
def connection = new SPIConnection((System.getenv("SPI_BUS") ?: "0").toInteger(), (System.getenv("SPI_DEVICE") ?: "0").toInteger(), 3, 1_000_000)// open SPI, Mode 3, 1 MHz, (bus, device, mode, maxSpeedHz) → SPIConnection
try {
    def motor = new TMC2130Full(connection, stepPin, dirPin, 0.11, enPin, null, null)// create Full driver, (connection, stepPin, dirPin, rSenseOhm=0.11 Ω, enPin, diag0, diag1) → TMC2130Full
    int homingSpeed = 150
    int burst = 20

    // --- Bring up the link and the power stage ---
    // IOIN.VERSION proves the SPI wiring and Mode 3 setting work before any
    // motion is attempted.
    def ioin = motor.readIoin()                                                   // read IOIN, () → Ioin
    println("version 0x" + Integer.toHexString(ioin.version()))
    motor.setCurrent(0.8)                                                         // set run current, (rmsA A RMS) → void
    motor.setMicrosteps(16)                                                       // set microstep resolution, (n 1–256) → void
    motor.enable()                                                                // power stage on, () → void

    // --- Arm StallGuard2 for the homing speed ---
    // StallGuard2 needs SpreadCycle and TSTEP <= TCOOLTHRS, so TCOOLTHRS is set
    // just above the TSTEP of the homing speed; sgt = 0 is the usual start value.
    motor.setChopperMode(true)                                                    // choose chopper, (spreadcycle) → void
    motor.setStallThreshold(0)                                                    // set StallGuard2 threshold, (sgt -64..63) → void
    motor.setCoolstepThreshold(12_000_000L / (homingSpeed * 256 / 2))             // set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → void

    // --- Drive toward the end stop in short bursts ---
    // The first burst is spin-up, where SG_RESULT is not yet valid; after each
    // burst the demo reads SG_RESULT until the stall flag is set.
    List<Integer> history = []
    motor.move(burst, homingSpeed)                                                // move, (fullSteps, fullStepsPerS full steps/s) → void
    while (true) {
        motor.move(burst, homingSpeed)                                            // move, (fullSteps, fullStepsPerS full steps/s) → void
        def sg = motor.readStallguard()                                           // read StallGuard2 result, () → int 0–1023
        history.add(sg)
        println("SG_RESULT " + sg)
        if (motor.isStalled()) break                                              // read stall flag, () → boolean
        def fault = motor.readFault()                                             // read fault bitmask, () → int
        if (fault != 0) {
            motor.disable()                                                       // power stage off, () → void
            println("fault 0x" + Integer.toHexString(fault))
            System.exit(1)
        }
    }

    // --- End stop found: report and back off ---
    // The last few SG_RESULT values show how the load built up before the stall.
    println("history " + history.subList(Math.max(0, history.size() - 5), history.size()))
    motor.move(-200, homingSpeed)                                                 // move, (fullSteps, fullStepsPerS full steps/s) → void

    // --- Final move with CoolStep ---
    // CoolStep lowers the current while the load is light; CS_ACTUAL shows it.
    motor.configureCoolstep(5, 2, 1, 1, 0)                                        // configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → void
    10.times {
        motor.move(20, homingSpeed)                                               // move, (fullSteps, fullStepsPerS full steps/s) → void
        println("CS_ACTUAL " + motor.readCurrentScale())                              // read actual current scale, () → int 0–31
    }

    // --- Shut down and report ---
    // GSTAT shows whether the run stressed the driver; the chip clears it on read.
    println("status " + motor.readStatus())  // read driver status, () → Status
    println("global status " + motor.readGlobalStatus())  // read GSTAT, () → GlobalStatus
    motor.disable()                                                               // power stage off, () → void
} finally {
    connection.close()
}
