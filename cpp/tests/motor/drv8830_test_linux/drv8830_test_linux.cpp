#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif

#include <stdio.h>
#include "I2CConnectionLinux.h"
#include "DRV8830.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, DRV8830Minimal::I2C_ADDRESS);

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

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
