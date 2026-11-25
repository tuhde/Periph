#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "UARTConnectionZephyr.h"
#include "TMC2209.h"

#define LOG(...) printk(__VA_ARGS__)
#define SLEEP_MS(ms) k_msleep(ms)

#define UART_NODE DT_NODELABEL(uart1)

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { LOG("PASS %s\n", label); passed++; }
    else      { LOG("FAIL %s\n", label); failed++; }
}

static void run_tests(Connection& connection) {
    TMC2209Full motor(connection);                          // Create TMC2209 Full driver, (connection, r_sense_ohm=0.11 Ω, address=0, en_pin=nullptr, echo=true)
    check_true(motor.ok(), "init_ok");
    if (!motor.ok()) { LOG("===DONE: %d passed, %d failed===\n", passed, failed + 1); return; }

    TMC2209Full::Ioin io = motor.readIoin();                // Read IOIN, () → Ioin
    check_true(io.version == 0x21, "ioin_version");
    motor.setStallThreshold(60);                            // Set StallGuard4 threshold, (sgthrs 0–255) → void
    check_true(motor.readStallguard() % 2 == 0, "sg_result_even");  // Read StallGuard4 result, () → uint16_t
    uint8_t before = motor.readIfcnt();                     // Read write counter, () → uint8_t
    motor.setCurrent(0.5f);                                 // Set run current, (rms_a A RMS) → bool
    check_true(motor.readIfcnt() == (uint8_t)(before + 2), "ifcnt_advances");
    motor.setMicrosteps(16);                                // Set microstep resolution, (n 1–256) → bool
    check_true(((motor.readRegister(0x6C) >> 24) & 0xF) == 4, "mres_readback");
    check_true((motor.readRegister(0x00) & 0x40) == 0x40, "gconf_pdn_disable");
    motor.enable();                                         // Power stage on, () → void
    motor.run(100.0f);                                      // Spin with the internal generator, (steps_per_s) → void
    SLEEP_MS(1000);
    check_true(!motor.isStandstill(), "moving");
    check_true(motor.readTstep() > 0, "tstep_nonzero");
    check_true(motor.readFault() == 0, "no_fault");
    motor.stop();                                           // Stop the motor, () → void
    SLEEP_MS(500);
    motor.disable();                                        // Power stage off, () → void
    check_true(motor.readStatus().csActual <= 31, "status_decoded");

    LOG("===DONE: %d passed, %d failed===\n", passed, failed);
}

int main(void) {
    const struct device* dev = DEVICE_DT_GET(UART_NODE);
    if (!device_is_ready(dev)) {
        printk("FAIL device_ready\n");
        return 1;
    }
    UARTConnectionZephyr connection(dev);
    run_tests(connection);
    return 0;
}
