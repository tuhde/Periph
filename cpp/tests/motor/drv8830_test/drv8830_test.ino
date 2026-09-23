#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include "I2CConnection.h"
#include "DRV8830.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, DRV8830Minimal::I2C_ADDRESS);

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

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {}
