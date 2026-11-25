#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, DS3502Full::I2C_ADDRESS);
DS3502Full pot(connection, 15.0f, 0.0f);                // Create DS3502 Full driver, (connection, vHigh=1.0 V, vLow=0.0 V)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    // --- Power-up position ---
    // The constructor leaves the wiper where the IVR put it (factory default 0x40).
    uint8_t up = pot.getWiper();                        // Read wiper position, () → uint8_t 0–127
    Serial.print("power-up wiper "); Serial.print((int)up); Serial.print(" -> "); Serial.print((double)pot.getVoltage(), 3); Serial.println(" V");

    // --- Set 6.0 V ---
    pot.setVoltage(6.0f);                               // Set RW output voltage, (volts V) → void
    float ratio = pot.getRatio();                       // Read divider ratio, () → float
    Serial.print("code "); Serial.print((int)pot.getWiper()); Serial.print(" ratio "); Serial.print((double)ratio, 3); Serial.print(" voltage "); Serial.print((double)pot.getVoltage(), 3); Serial.println(" V");

    // --- Sweep in 16 steps ---
    // The step size is (V_RH − V_RL)/127, about 118 mV at a 15 V span.
    for (int code = 0; code <= 127; code += 8) {
        pot.setWiper(code);                             // Set wiper position, (code 0–127) → void
        Serial.print("code "); Serial.print(code); Serial.print(" -> "); Serial.print((double)pot.getVoltage(), 3); Serial.println(" V");
        delay(200);
    }
    pot.setWiper(127);

    // --- Step up and down ---
    pot.setVoltage(6.0f);
    uint8_t up4 = pot.increment(4);                     // Step the wiper, (steps=1) → uint8_t 0–127
    uint8_t dn4 = pot.increment(-4);
    Serial.print("+4 -> "); Serial.print((int)up4); Serial.print(", -4 -> "); Serial.println((int)dn4);

    // --- MODE ---
    // MODE = 1 (the working mode) keeps wiper writes in SRAM; storeWiper switches
    // to MODE = 0 only for the duration of the EEPROM write.
    bool eeprom = pot.getMode();                        // Read MODE, () → bool
    Serial.print("eeprom mode "); Serial.println((int)eeprom);
    pot.setMode(false);                                 // Select SRAM-only writes, (eeprom bool) → void

    // --- Persist the chosen code ---
    // This consumes one of the EEPROM's limited write cycles.
    int code = pot.getWiper();
    bool stored = pot.storeWiper(code);                 // Store power-up position, (code=-1) → bool
    bool ready = pot.waitReady();                       // Acknowledge polling, (timeoutMs=30) → bool
    Serial.print("stored "); Serial.print((int)stored); Serial.print(", ready "); Serial.print((int)ready); Serial.print(", read back "); Serial.println((int)pot.getWiper());
}

void loop() {
}
