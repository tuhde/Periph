#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x34);

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    static AXP2101Minimal chip(connection);               // Create AXP2101 driver, (connection)
    float v = chip.batteryVoltage();                    // Read battery voltage, () → float V
    uint8_t pct = chip.batteryPercent();                // Read state of charge, () → uint8_t %
    uint8_t state = chip.chargeState();                 // Read charge state, () → uint8_t (0 trickle … 5 none)
    bool vbus = chip.vbusPresent();                     // Check input source, () → bool
    Serial.print(v, 3); Serial.print(" V  "); Serial.print(pct); Serial.print(" %  state="); Serial.print(state); Serial.print("  vbus="); Serial.print((vbus) ? "true" : "false"); Serial.println();
    delay(1000);
}
