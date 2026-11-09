// TMC2208 Complete example: exercise every method in the public API.
// Wiring: MCU TX -> 1 kOhm -> PDN_UART, MCU RX -> PDN_UART directly (echo handling is on).
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/gpio.h>
#include "UARTConnectionZephyr.h"
#include "OutputPinZephyr.h"
#include "TMC2208.h"

#define LOG(...) printk(__VA_ARGS__)
#define SLEEP_MS(ms) k_msleep(ms)

#ifndef TMC2208_UART_NODE
#define TMC2208_UART_NODE DT_NODELABEL(uart1)
#endif

static void example(Connection& connection) {
    static const struct gpio_dt_spec en_spec = { .port = DEVICE_DT_GET(DT_NODELABEL(gpio0)), .pin = 10, .dt_flags = 0 };
    OutputPinZephyr en(en_spec);
    static const struct gpio_dt_spec stp_spec = { .port = DEVICE_DT_GET(DT_NODELABEL(gpio0)), .pin = 11, .dt_flags = 0 };
    OutputPinZephyr stp(stp_spec);
    static const struct gpio_dt_spec dir_spec = { .port = DEVICE_DT_GET(DT_NODELABEL(gpio0)), .pin = 12, .dt_flags = 0 };
    OutputPinZephyr dir(dir_spec);
    TMC2208Full motor(connection, 0.11f, &en, true, &stp, &dir);  // Create TMC2208 Full driver, (connection, r_sense_ohm=0.11 Ω, en_pin, echo=true, step_pin, dir_pin, fclk_hz=12000000 Hz, tmc2224=false)
                                                         // reads IOIN to verify the chip, writes the default configuration and checks IFCNT
    if (!motor.ok()) { LOG("TMC2208 not found\n"); return; }

    motor.enable();                                      // Power stage on, () → void
                                                         // drives ENN low
    motor.setCurrent(0.8f);                              // Set run current, (rms_a A RMS) → bool
                                                         // converts amperes to the 0–31 scale; hold current becomes a third
    motor.setHoldCurrent(0.2f);                          // Set hold current, (rms_a A RMS) → bool
                                                         // overrides the automatic one-third hold current
    motor.setCurrentDelay(6, 20);                        // Set power-down timing, (ihold_delay 0–15, tpowerdown 0–255) → bool
                                                         // IHOLDDELAY and TPOWERDOWN, units of 2^18 clocks
    motor.setMicrosteps(32);                             // Set microstep resolution, (n 1–256) → bool
                                                         // writes CHOPCONF.MRES
    motor.setInterpolation(true);                        // Interpolate to 256 microsteps, (enabled) → void
                                                         // sets CHOPCONF.intpol
    motor.setChopperMode(false);                         // Choose chopper, (spreadcycle) → void
                                                         // false selects StealthChop
    motor.setStealthchopThreshold(300);                  // Set StealthChop velocity limit, (tpwmthrs 20 bit) → void
                                                         // faster than this the chip switches to SpreadCycle
    motor.configureChopper(3, 2, 5, 0);                  // Configure chopper timing, (toff, tbl, hstrt, hend) → void
                                                         // direct CHOPCONF fields
    motor.configureStealthchop(true, true, 36, 14, 1, 4, 12);  // Configure StealthChop, (autoscale, autograd, pwm_ofs, pwm_grad, pwm_freq, pwm_reg, pwm_lim) → void
                                                         // direct PWMCONF fields
    motor.setFreewheel(0);                               // Set standstill mode, (mode 0–3) → bool
                                                         // PWMCONF.freewheel, only effective when IHOLD is 0
    motor.setVsense(false);                              // Select sense range, (high_sensitivity) → void
                                                         // false keeps the 0.325 V full scale
    motor.setAnalogScaling(true);                        // Current reference source, (enabled) → void
                                                         // true takes the reference from VREF
    motor.setDirectionInverted(false);                   // Invert direction, (inverted) → void
                                                         // GCONF.shaft
    motor.setIndexOutput(0);                             // Select INDEX output, (mode 0–2) → bool
                                                         // 0 first microstep, 1 pre-warning, 2 step pulses
    motor.setDriverProtection(true, true);               // Short protections, (short_ls, short_gnd) → void
                                                         // true keeps the protection enabled
    motor.setDoubleEdge(false);                          // Step on both edges, (enabled) → void
                                                         // CHOPCONF.dedge
    motor.setSendDelay(0);                               // Set reply delay, (n 0–15) → bool
                                                         // NODECONF.SENDDELAY

    motor.run(300.0f);                                   // Spin with the internal generator, (steps_per_s full steps/s) → void
                                                         // converts to VACTUAL using the 12 MHz clock
    SLEEP_MS(2000);
    LOG("position=%d\n", (int)motor.microstepPosition());   // Read microstep counter, () → uint16_t 0–1023
                                                         // MSCNT
    TMC2208Full::CoilCurrents cc = motor.readCoilCurrents(); // Read coil currents, () → CoilCurrents {int16_t low, high}
                                                         // two signed 9-bit MSCURACT fields
    LOG("currents=%d,%d\n", (int)cc.low, (int)cc.high);
    LOG("tstep=%lu\n", (unsigned long)motor.readTstep());   // Read step interval, () → uint32_t clocks
                                                         // time between 1/256 microsteps
    LOG("scale=%d\n", (int)motor.readCurrentScale());    // Read actual current scale, () → uint8_t 0–31
                                                         // DRV_STATUS.CS_ACTUAL
    LOG("temp=%d\n", (int)motor.readTemperatureFlags()); // Read temperature flags, () → uint8_t 0–15
                                                         // bit 0 t120 … bit 3 t157
    TMC2208Full::Status st = motor.readStatus();         // Read driver status, () → Status
                                                         // decoded DRV_STATUS
    LOG("stealth=%d cs=%d\n", (int)st.stealth, (int)st.csActual);
    LOG("standstill=%d\n", (int)motor.isStandstill());   // Read standstill flag, () → bool
                                                         // DRV_STATUS.stst
    LOG("fault=%d\n", (int)motor.readFault());           // Read fault bitmask, () → uint8_t
                                                         // FAULT_* constants, 0 = none
    motor.stop();                                        // Stop the motor, () → void
                                                         // VACTUAL = 0, STEP input takes over again

    motor.step(400, 0.001f);                             // Pulse the STEP pin, (count, delay_s s) → bool
                                                         // negative counts reverse via DIR
    TMC2208Full::GlobalStatus gs = motor.readGlobalStatus(true);  // Read and clear GSTAT, (clear=false) → GlobalStatus
                                                         // reset, drv_err, uv_cp
    LOG("reset=%d drv_err=%d\n", (int)gs.reset, (int)gs.drvErr);
    LOG("ifcnt=%d\n", (int)motor.readIfcnt());           // Read write counter, () → uint8_t
                                                         // IFCNT
    TMC2208Full::Ioin io = motor.readIoin();             // Read IOIN, () → Ioin {raw, selA, version}
                                                         // raw word, SEL_A and silicon version
    LOG("version=0x%02X\n", (unsigned)io.version);
    LOG("otp=0x%06lX\n", (unsigned long)motor.readOtp()); // Read OTP image, () → uint32_t
                                                         // OTP_READ
    TMC2208Full::PwmAuto pa = motor.readPwmAuto();       // Read StealthChop tuning, () → PwmAuto
                                                         // PWM_SCALE and PWM_AUTO results
    LOG("pwm_ofs_auto=%d\n", (int)pa.pwmOfsAuto);
    LOG("gconf=0x%lX\n", (unsigned long)motor.readRegister(0x00));  // Read a raw register, (reg) → uint32_t
                                                         // any readable register
    motor.writeRegister(0x13, 300);                      // Write a raw register, (reg, value) → bool
                                                         // here TPWMTHRS again
    motor.disable();                                     // Power stage off, () → void
                                                         // drives ENN high; the motor freewheels
}

int main(void) {
    const struct device* dev = DEVICE_DT_GET(TMC2208_UART_NODE);
    UARTConnectionZephyr connection(dev);
    example(connection);
    while (true) k_msleep(1000);
    return 0;
}
