///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

// TMC2130 minimal: move a stepper with STEP/DIR pulses.
// Wiring: SPI Mode 3 (SPI_MODE pin high); STEP/DIR/ENN on GPIO 27/22/17 (DIAG0 on GPIO 23 for the complete example).

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.connection.SysfsOutputPin;
import it.uhde.periph.chips.motor.TMC2130Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        var stepPin = new SysfsOutputPin(27);                                         // create STEP pin, (gpio=27) → OutputPin
        var dirPin = new SysfsOutputPin(22);                                          // create DIR pin, (gpio=22) → OutputPin
        try (var connection = new SPIConnection(Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0")), Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0")), 3, 1_000_000)) {// open SPI, Mode 3, 1 MHz, (bus, device, mode, maxSpeedHz) → SPIConnection
            var motor = new TMC2130Minimal(connection, stepPin, dirPin, 0.11, null);      // create driver, (connection, stepPin, dirPin, rSenseOhm=0.11 Ω, enPin=null) → TMC2130Minimal
            motor.setCurrent(0.8);                                                        // set run current, (rmsA A RMS) → void
            motor.enable();                                                               // power stage on, () → void
            for (int steps : new int[] {400, -400}) {
                motor.move(steps, 200.0);                                                 // move, (fullSteps, fullStepsPerS full steps/s) → void
                Thread.sleep(500);
                System.out.println("standstill: " + motor.isStandstill());                    // read standstill flag, () → boolean
                System.out.println("fault: " + motor.readFault());                            // read fault bitmask, () → int
            }
            motor.disable();                                                              // power stage off, () → void
        }
    }
}
