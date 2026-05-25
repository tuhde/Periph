#include <Wire.h>
#include "I2CTransport.h"
#include "AS5600.h"

I2CTransport transport(Wire, 0x36);
AS5600Full as5600(transport);

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin();

    // --- Status and magnet checks ---
    Serial.println(as5600.is_magnet_detected());       // Check magnet present, () → bool
    Serial.println(as5600.is_magnet_too_strong());     // Check magnet too strong, () → bool
    Serial.println(as5600.is_magnet_too_weak());       // Check magnet too weak, () → bool
    Serial.println(as5600.status_byte(), HEX);         // Read raw status, () → int

    // --- Angle readings ---
    Serial.println(as5600.angle());                    // Read absolute angle, () → float degrees
    Serial.println(as5600.angle_raw());                // Read scaled angle count, () → int 0-4095
    Serial.println(as5600.raw_angle());                // Read raw unscaled angle, () → int 0-4095
    Serial.println(as5600.raw_angle_degrees());        // Read raw angle in degrees, () → float degrees

    // --- Diagnostics ---
    Serial.println(as5600.agc());                      // Read AGC value, () → int
    Serial.println(as5600.magnitude());                // Read CORDIC magnitude, () → int

    // --- Position configuration (volatile) ---
    Serial.println(as5600.zero_position());            // Read ZPOS, () → int 0-4095
    Serial.println(as5600.max_position());             // Read MPOS, () → int 0-4095
    Serial.println(as5600.max_angle());                // Read MANG, () → int 0-4095

    as5600.set_zero_position(0);                       // Set zero position, (pos 0-4095) → None
    as5600.set_max_position(4095);                     // Set max position, (pos 0-4095) → None
    as5600.set_max_angle(2048);                        // Set max angle span, (span 0-4095) → None

    // --- Configure power mode and output ---
    as5600.configure(AS5600Full::PM_NOM, 0, AS5600Full::OUTS_ANALOG, 0, 0, 0, false);  // Configure chip, (pm 0-3, hyst 0-3, outs 0-2, pwmf 0-3, sf 0-3, fth 0-7, wd bool) → None

    // --- Burn count ---
    Serial.println(as5600.burn_count());               // Read burn count, () → int 0-3
}

void loop() {
    delay(1000);
}
