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

void onFault(const DRV8830Full::Fault& f) {
    Serial.print("fault interrupt ocp="); Serial.print(f.ocp);
    Serial.print(" uvlo="); Serial.print(f.uvlo);
    Serial.print(" ots="); Serial.print(f.ots);
    Serial.print(" ilimit="); Serial.println(f.ilimit);
}

void setup() {
    Serial.begin(115200);
    Wire.begin();

    motor.drive(2.5f);                                   // Drive at regulated voltage, (voltage V, + = forward) → void
                                                         // maps 2.5 V to the nearest VSET code and sets IN1=1, IN2=0
    delay(1000);
    DRV8830Full::Output out = motor.readOutput();        // Read back CONTROL, () → Output {float V, Direction}
                                                         // decodes VSET to volts and IN1/IN2 to a Direction
    Serial.print("commanded "); Serial.print(out.voltage); Serial.print(" V ");
    Serial.println(directionName(out.direction));

    motor.drive(-1.5f);                                  // Drive at regulated voltage, (voltage V, - = reverse) → void
                                                         // a negative voltage sets IN1=0, IN2=1
    delay(1000);

    bool ok = motor.setOutput(37, true, false);          // Write raw CONTROL fields, (vset 6–63, in1, in2) → bool
                                                         // VSET 37 is ~2.97 V forward; codes 0–5 are rejected (false)
    delay(1000);

    motor.brake();                                       // Short-brake, () → void
                                                         // IN1=IN2=1 drives both outputs high
    delay(500);
    motor.stop();                                        // Coast to standby, () → void
                                                         // IN1=IN2=0 leaves both outputs high-impedance

    DRV8830Full::Fault f = motor.readFault();            // Read fault status, () → Fault {fault, ocp, uvlo, ots, ilimit}
                                                         // does not clear — latched OCP/ILIMIT keep the bridge off
    Serial.print("setOutput="); Serial.print(ok);
    Serial.print(" fault="); Serial.println(f.fault);
    motor.clearFault();                                  // Clear fault bits, () → void
                                                         // writes CLEAR=1; re-enables a latched-off bridge

    motor.onInterrupt(onFault);                          // Subscribe to FAULTn, (callback, intPin=nullptr) → void
                                                         // callback receives the readFault() result
    DRV8830Full::Fault p = motor.pollInterrupt();        // Poll fault status, () → Fault
                                                         // same as readFault(); never clears implicitly
    motor.offInterrupt();                                // Unsubscribe, () → void
    Serial.print("poll fault="); Serial.println(p.fault);
}

void loop() {}
