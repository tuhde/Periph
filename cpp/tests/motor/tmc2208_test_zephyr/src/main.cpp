#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "UARTConnectionZephyr.h"
#include "TMC2208.h"

#define LOG(...) printk(__VA_ARGS__)
#define SLEEP_MS(ms) k_msleep(ms)

#define UART_NODE DT_NODELABEL(uart1)

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { LOG("PASS %s\n", label); passed++; }
    else      { LOG("FAIL %s\n", label); failed++; }
}

static void run_tests(Connection& connection) {
    TMC2208Full motor(connection);                          // Create TMC2208 Full driver, (connection, r_sense_ohm=0.11 Ω, en_pin=nullptr, echo=true)
    check_true(motor.ok(), "init_ok");
    if (!motor.ok()) { LOG("===DONE: %d passed, %d failed===\n", passed, failed + 1); return; }

    TMC2208Full::Ioin io = motor.readIoin();                // Read IOIN, () → Ioin
    check_true(io.version != 0x00 && io.version != 0xFF, "ioin_version");
    check_true(io.selA, "ioin_sel_a");
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
