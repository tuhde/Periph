///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-kotlin:1.3.3

// TMC2130 minimal: move a stepper with STEP/DIR pulses.
// Wiring: SPI Mode 3 (SPI_MODE pin high); STEP/DIR/ENN on GPIO 27/22/17 (DIAG0 on GPIO 23 for the complete example).

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.connection.SysfsOutputPin
import it.uhde.periph.chips.motor.TMC2130Minimal

fun main() {
    val stepPin = SysfsOutputPin(27)                                              // create STEP pin, (gpio=27) → OutputPin
    val dirPin = SysfsOutputPin(22)                                               // create DIR pin, (gpio=22) → OutputPin
    SPIConnection(System.getenv("SPI_BUS")?.toIntOrNull() ?: 0, System.getenv("SPI_DEVICE")?.toIntOrNull() ?: 0, 3, 1_000_000).use { connection ->// open SPI, Mode 3, 1 MHz, (bus, device, mode, maxSpeedHz) → SPIConnection
        val motor = TMC2130Minimal(connection, stepPin, dirPin, 0.11, null)           // create driver, (connection, stepPin, dirPin, rSenseOhm=0.11 Ω, enPin=null) → TMC2130Minimal
        motor.setCurrent(0.8)                                                         // set run current, (rmsA A RMS) → void
        motor.enable()                                                                // power stage on, () → void
        for (steps in intArrayOf(400, -400)) {
            motor.move(steps, 200.0)                                                  // move, (fullSteps, fullStepsPerS full steps/s) → void
            Thread.sleep(500)
            println("standstill: " + motor.isStandstill())                                // read standstill flag, () → boolean
            println("fault: " + motor.readFault())                                        // read fault bitmask, () → int
        }
        motor.disable()                                                               // power stage off, () → void
    }
}
