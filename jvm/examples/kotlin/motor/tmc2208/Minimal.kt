///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

// TMC2208 minimal: spin a stepper with the internal step generator.
// Wiring: MCU TX -> 1 kOhm -> PDN_UART, MCU RX -> PDN_UART directly (echo handling is on).

import it.uhde.periph.connection.UARTConnection
import it.uhde.periph.chips.motor.TMC2208Minimal

fun main() {
    UARTConnection(System.getenv("UART_PORT") ?: "/dev/ttyS0", 115200, 8, 1.0, 'N', 100, -1).use { connection ->  // open UART, 115200 8N1, (port, baudRate, dataBits, stopBits, parity, timeoutMs, dePinNum) → UARTConnection
        val motor = TMC2208Minimal(connection, 0.11, null, true)                // create driver, (connection, rSenseOhm=0.11 Ω, enPin=null, echo=true) → TMC2208Minimal

        motor.setCurrent(0.8)                                                        // set run current, (rmsA A RMS) → void
        for (speed in doubleArrayOf(200.0, -200.0)) {
            motor.run(speed)                                                         // spin with the internal generator, (stepsPerS full steps/s, sign = direction) → void
            Thread.sleep(3000)
            println("standstill=" + motor.isStandstill() + " fault=" + motor.readFault())  // isStandstill() → boolean, readFault() → int bitmask
        }

        motor.stop()                                                                 // stop the motor, () → void
    }
}
