#include <Wire.h>
#include "I2CConnection.h"
#include "DRV8830.h"

I2CConnection connection(Wire, DRV8830Minimal::I2C_ADDRESS);
DRV8830Full motor(connection);                           // Create DRV8830 Full driver, (connection)

static const char* directionName(DRV8830Full::Direction d) {
    switch (d) {
        case DRV8830Full::Direction::Forward: return "forward";
        case DRV8830Full::Direction::Reverse: return "reverse";
        case DRV8830Full::Direction::Brake:   return "brake";
        default:                              return "coast";
    }
}

static void checkFault() {
    // --- Recover from a fault instead of leaving the bridge latched off ---
    // OCP and ILIMIT disable the H-bridge until CLEAR is written; stop first
    // so the motor does not lurch back to the old command on clear.
    DRV8830Full::Fault f = motor.readFault();            // Read fault status, () → Fault
    if (f.fault) {
        Serial.print("fault:");
        if (f.ocp)    Serial.print(" OCP");
        if (f.uvlo)   Serial.print(" UVLO");
        if (f.ots)    Serial.print(" OTS");
        if (f.ilimit) Serial.print(" ILIMIT");
        Serial.println();
        motor.stop();                                    // Coast to standby, () → void
        motor.clearFault();                              // Clear fault bits, () → void
    }
}

static void run(float voltage, int seconds) {
    // --- Hold a regulated voltage and watch it stay put ---
    // The chip PWM-regulates the bridge against VCC internally, so the
    // commanded voltage (and motor speed) holds while the battery discharges.
    motor.drive(voltage);                                // Drive at regulated voltage, (voltage V, signed) → void
    checkFault();
    for (int i = 0; i < seconds; ++i) {
        delay(1000);
        DRV8830Full::Output out = motor.readOutput();    // Read back CONTROL, () → Output {float V, Direction}
        Serial.print(directionName(out.direction)); Serial.print(' ');
        Serial.print(out.voltage); Serial.println(" V");
    }
}

void setup() {
    Serial.begin(115200);
    Wire.begin();

    // --- Battery-powered toy: constant speed forward, then reverse ---
    run(3.0f, 5);
    run(-2.0f, 5);

    // --- Stop quickly, then release ---
    // Braking shorts the winding for a fast stop; coasting afterwards removes
    // the load so the motor does not sit shorted indefinitely.
    motor.brake();                                       // Short-brake, () → void
    delay(500);
    motor.stop();                                        // Coast to standby, () → void
}

void loop() {}
