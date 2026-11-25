#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, DS3502Full::I2C_ADDRESS);
DS3502Full pot(connection, 15.0f, 0.0f);                // Create DS3502 Full driver, (connection, vHigh=1.0 V, vLow=0.0 V)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    pot.setWiper(100);                                  // Set wiper position, (code 0–127) → void
    // writes WR only; the EEPROM is not touched
    uint8_t w = pot.getWiper();                         // Read wiper position, () → uint8_t 0–127
    // reads WR; always the live wiper, never the IVR
    Serial.print("wiper "); Serial.println((int)w);
    pot.setVoltage(6.0f);                               // Set RW output voltage, (volts V) → void
    // nearest code of V = V_RL + (V_RH − V_RL)·code/127
    float v = pot.getVoltage();                         // Read unloaded RW voltage, () → float V
    // evaluates the divider formula for the current code
    Serial.print("voltage "); Serial.print((double)v, 3); Serial.println(" V");
    pot.setRatio(0.25f);                                // Set divider ratio, (ratio 0.0–1.0) → void
    // code = round(ratio × 127)
    float ratio = pot.getRatio();                       // Read divider ratio, () → float
    // code / 127
    Serial.print("ratio "); Serial.println((double)ratio, 4);
    float rl = pot.getResistance('L');                  // Read wiper-to-RL resistance, (terminal='L') → float Ω
    // nominal, RTOTAL = 10 kΩ ±20 %
    float rh = pot.getResistance('H');                  // Read wiper-to-RH resistance, (terminal='L') → float Ω
    Serial.print("R_WL "); Serial.print((double)rl, 1); Serial.print(" ohm, R_WH "); Serial.print((double)rh, 1); Serial.println(" ohm");
    uint8_t n = pot.increment(5);                       // Step the wiper, (steps=1) → uint8_t 0–127
    // read, add, clamp, write; returns the new code
    Serial.print("incremented to "); Serial.println((int)n);
    bool eeprom = pot.getMode();                        // Read MODE, () → bool
    // true if writes to 0x00 also program the EEPROM
    Serial.print("eeprom mode "); Serial.println((int)eeprom);
    pot.setMode(false);                                 // Select SRAM-only writes, (eeprom bool) → void
    // CR = 0x80; true would write CR = 0x00
    bool stored = pot.storeWiper(64);                   // Store power-up position, (code=-1) → bool
    // MODE = 0, write WR+IVR, wait for the EEPROM, MODE = 1
    Serial.print("stored "); Serial.println((int)stored);
    bool ready = pot.waitReady();                       // Acknowledge polling, (timeoutMs=30) → bool
    // false if the chip keeps NACKing
    Serial.print("ready "); Serial.println((int)ready);
    pot.setEndpoints(12.0f, 2.0f);                      // Change RH / RL voltages, (vHigh V, vLow=0.0 V) → void
    // affects the conversions only; no bus traffic
}

void loop() {
}
