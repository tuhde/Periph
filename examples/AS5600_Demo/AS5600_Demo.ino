#include <Wire.h>
#include "I2CTransport.h"
#include "AS5600.h"

I2CTransport transport(Wire, 0x36);
AS5600Full as5600(transport);

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin();

    // --- Motor feedback monitor: read angle 10 times per second ---
    // AGC monitoring detects magnet distance drift; status changes alert to magnet removal.
    // In 5 V mode, target AGC ≈ 128; in 3.3 V mode, target AGC ≈ 64.

    uint8_t prev_status = as5600.status_byte();

    for (int n = 0; n < 10; n++) {
        float a = as5600.angle();                    // Read absolute angle, () → float degrees
        uint16_t r = as5600.raw_angle();             // Read raw unscaled angle, () → int 0-4095
        uint8_t g = as5600.agc();                    // Read AGC value, () → int

        // --- Check for status changes (magnet inserted/removed) ---
        uint8_t status = as5600.status_byte();
        if (status != prev_status) {
            if (!as5600.is_magnet_detected()) {
                Serial.println("[MAGNET REMOVED] MD=0");
            } else {
                Serial.print("[MAGNET DETECTED] MD=1  MH=");
                Serial.print(as5600.is_magnet_too_strong());
                Serial.print("  ML=");
                Serial.println(as5600.is_magnet_too_weak());
            }
            prev_status = status;
        }

        // --- AGC health check ---
        if (as5600.is_magnet_detected()) {
            const char* tag = "[OK]";
            if (g < 64 || g > 192) {
                tag = "[AGC low — magnet weak or too far]";
            }
            Serial.print("angle="); Serial.print(a, 2); Serial.print("°  raw=");
            Serial.print(r); Serial.print("  agc="); Serial.print(g);
            Serial.println(tag);
        }

        delay(100);
    }
}

void loop() {
    delay(1000);
}
