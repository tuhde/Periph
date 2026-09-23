#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "DRV8830.h"

#define I2C_NODE DT_NODELABEL(i2c0)

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    if (!device_is_ready(i2c_dev)) {
        printk("FAIL device_ready\n");
        return 1;
    }
    I2CConnectionZephyr connection(i2c_dev, DRV8830Minimal::I2C_ADDRESS);
    DRV8830Minimal motor(connection);                       // Create DRV8830 driver, (connection)
    motor.drive(2.0f);                                      // Drive at regulated voltage, (voltage V, + = forward) → void
    motor.stop();                                           // Coast to standby, () → void

    DRV8830Full full(connection);                           // Create DRV8830 Full driver, (connection)
    full.drive(2.0f);                                       // Drive at regulated voltage, (voltage V, + = forward) → void
    DRV8830Full::Output out = full.readOutput();            // Read back CONTROL, () → Output {float V, Direction}
    check_true(out.direction == DRV8830Full::Direction::Forward, "drive_forward_direction");
    check_true(out.voltage > 1.9f && out.voltage < 2.1f, "drive_forward_voltage");

    full.drive(-1.0f);                                      // Drive at regulated voltage, (voltage V, - = reverse) → void
    check_true(full.readOutput().direction == DRV8830Full::Direction::Reverse, "drive_reverse_direction");

    full.brake();                                           // Short-brake, () → void
    check_true(full.readOutput().direction == DRV8830Full::Direction::Brake, "brake_direction");

    full.stop();                                            // Coast to standby, () → void
    check_true(full.readOutput().direction == DRV8830Full::Direction::Coast, "stop_direction");

    full.clearFault();                                      // Clear fault bits, () → void
    check_true(!full.readFault().fault, "clear_fault");     // Read fault status, () → Fault

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
