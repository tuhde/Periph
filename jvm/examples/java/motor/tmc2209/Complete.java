///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-java:1.3.3

// TMC2209 complete: exercise every method in the public API.
// Wiring: MCU TX -> 1 kOhm -> PDN_UART, MCU RX -> PDN_UART directly (echo handling is on).

import it.uhde.periph.connection.UARTConnection;
import it.uhde.periph.connection.SysfsInputPin;
import it.uhde.periph.connection.SysfsOutputPin;
import it.uhde.periph.chips.motor.TMC2209Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        var enPin = new SysfsOutputPin(17);                                 // create ENN pin, (gpio=17) → OutputPin
        var stepPin = new SysfsOutputPin(27);                               // create STEP pin, (gpio=27) → OutputPin
        var dirPin = new SysfsOutputPin(22);                                // create DIR pin, (gpio=22) → OutputPin
        var diagPin = new SysfsInputPin(23);                                              // create DIAG pin, (gpio=23) → InputPin
        try (var connection = new UARTConnection(System.getenv().getOrDefault("UART_PORT", "/dev/ttyS0"), 115200, 8, 1.0, 'N', 100, -1)) {  // open UART, 115200 8N1, (port, baudRate, dataBits, stopBits, parity, timeoutMs, dePinNum) → UARTConnection
            var motor = new TMC2209Full(connection, 0.11, 0, enPin, true, stepPin, dirPin, diagPin, false, 12_000_000L);  // create Full driver, (connection, rSenseOhm=0.11 Ω, address=0, enPin, echo=true, stepPin, dirPin, diagPin, multiNode=false, fclkHz=12000000 Hz) → TMC2209Full
                                                                                           // reads IOIN to verify the chip, writes the default configuration and checks IFCNT

            motor.enable();                                                               // power stage on, () → void
                                                                                          // drives ENN low
            motor.setCurrent(0.8);                                                        // set run current, (rmsA A RMS) → void
                                                                                          // converts amperes to the 0–31 scale; hold current becomes a third
            motor.setHoldCurrent(0.2);                                                    // set hold current, (rmsA A RMS) → void
                                                                                          // overrides the automatic one-third hold current
            motor.setCurrentDelay(6, 20);                                                 // set power-down timing, (iholdDelay 0–15, tpowerdown 0–255) → void
                                                                                          // IHOLDDELAY and TPOWERDOWN, units of 2^18 clocks
            motor.setMicrosteps(32);                                                      // set microstep resolution, (n 1–256) → void
                                                                                          // writes CHOPCONF.MRES
            motor.setInterpolation(true);                                                 // interpolate to 256 microsteps, (enabled) → void
                                                                                          // sets CHOPCONF.intpol
            motor.setChopperMode(false);                                                  // choose chopper, (spreadcycle) → void
                                                                                          // false selects StealthChop
            motor.setStealthchopThreshold(300);                                           // set StealthChop velocity limit, (tpwmthrs 20 bit) → void
                                                                                          // faster than this the chip switches to SpreadCycle
            motor.configureChopper(3, 2, 5, 0);                                           // configure chopper timing, (toff, tbl, hstrt, hend) → void
                                                                                          // direct CHOPCONF fields
            motor.configureStealthchop(true, true, 36, 14, 1, 4, 12);                     // configure StealthChop, (autoscale, autograd, pwmOfs, pwmGrad, pwmFreq, pwmReg, pwmLim) → void
                                                                                          // direct PWMCONF fields
            motor.setFreewheel(0);                                                        // set standstill mode, (mode 0–3) → void
                                                                                          // PWMCONF.freewheel, only effective when IHOLD is 0
            motor.setVsense(false);                                                       // select sense range, (highSensitivity) → void
                                                                                          // false keeps the 0.325 V full scale
            motor.setAnalogScaling(true);                                                 // current reference source, (enabled) → void
                                                                                          // true takes the reference from VREF
            motor.setDirectionInverted(false);                                            // invert direction, (inverted) → void
                                                                                          // GCONF.shaft
            motor.setIndexOutput(0);                                                      // select INDEX output, (mode 0–2) → void
                                                                                          // 0 first microstep, 1 pre-warning, 2 step pulses
            motor.setDriverProtection(true, true);                                        // short protections, (shortLs, shortGnd) → void
                                                                                          // true keeps the protection enabled
            motor.setDoubleEdge(false);                                                   // step on both edges, (enabled) → void
                                                                                          // CHOPCONF.dedge
            motor.setStallThreshold(60);                                                  // set StallGuard4 threshold, (sgthrs 0–255) → void
                                                                                          // stall when SG_RESULT <= 2 * SGTHRS
            motor.setCoolstepThreshold(1000);                                             // set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → void
                                                                                          // active while TCOOLTHRS >= TSTEP > TPWMTHRS
            motor.configureCoolstep(5, 2, 1, 1, 0);                                       // configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → void
                                                                                          // semin = 0 would disable CoolStep
            motor.setSendDelay(0);                                                        // set reply delay, (n 0–15) → void
                                                                                          // NODECONF.SENDDELAY

            motor.run(300.0);                                                             // spin with the internal generator, (stepsPerS full steps/s) → void
                                                                                          // converts to VACTUAL using the 12 MHz clock
            Thread.sleep(2000);
            System.out.println("position " + motor.microstepPosition());                               // read microstep counter, () → int 0–1023
                                                                                          // MSCNT
            System.out.println("currents " + motor.readCoilCurrents());                                // read coil currents, () → CoilCurrents
                                                                                          // two signed 9-bit MSCURACT fields
            System.out.println("sg_result " + motor.readStallguard());                    // read StallGuard4 result, () → int 0–510
                                                                                          // SG_RESULT, higher = less load
            System.out.println("stalled " + motor.isStalled());                           // read DIAG pin, () → boolean
                                                                                          // true on a stall or a driver error
            System.out.println("tstep " + motor.readTstep());                                          // read step interval, () → long clocks
                                                                                          // time between 1/256 microsteps
            System.out.println("scale " + motor.readCurrentScale());                                   // read actual current scale, () → int 0–31
                                                                                          // DRV_STATUS.CS_ACTUAL
            System.out.println("temp " + motor.readTemperatureFlags());                                // read temperature flags, () → int 0–15
                                                                                          // bit 0 t120 … bit 3 t157
            System.out.println("status " + motor.readStatus());                                        // read driver status, () → Status
                                                                                          // decoded DRV_STATUS
            System.out.println("standstill " + motor.isStandstill());                                  // read standstill flag, () → boolean
                                                                                          // DRV_STATUS.stst
            System.out.println("fault " + motor.readFault());                                          // read fault bitmask, () → int
                                                                                          // FAULT_* constants, 0 = none
            motor.stop();                                                                 // stop the motor, () → void
                                                                                          // VACTUAL = 0, STEP input takes over again

            motor.step(400, 0.001);                                                       // pulse the STEP pin, (count, delayS s) → void
                                                                                          // negative counts reverse via DIR
            System.out.println("gstat " + motor.readGlobalStatus(true));                               // read and clear GSTAT, (clear) → GlobalStatus
                                                                                          // reset, drvErr, uvCp
            System.out.println("ifcnt " + motor.readIfcnt());                                          // read write counter, () → int
                                                                                          // IFCNT
            System.out.println("ioin " + motor.readIoin());                                            // read IOIN, () → Ioin
                                                                                          // raw word, selA and silicon version
            System.out.println("otp " + Long.toHexString(motor.readOtp()));                            // read OTP image, () → long
                                                                                          // OTP_READ
            System.out.println("pwm_auto " + motor.readPwmAuto());                                     // read StealthChop tuning, () → PwmAuto
                                                                                          // PWM_SCALE and PWM_AUTO results
            System.out.println("gconf " + Long.toHexString(motor.readRegister(0x00)));                 // read a raw register, (reg) → long
                                                                                          // any readable register
            motor.writeRegister(0x13, 300);                                               // write a raw register, (reg, value) → void
                                                                                          // here TPWMTHRS again
            motor.disable();                                                              // power stage off, () → void
                                                                                          // drives ENN high; the motor freewheels
        }
    }
}
