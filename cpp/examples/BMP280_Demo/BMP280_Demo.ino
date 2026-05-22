#include <Wire.h>
#include "I2CTransport.h"
#include "BMP280.h"

I2CTransport transport(Wire, 0x76);
BMP280Full bmp(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();

    // --- Weather monitoring preset: forced mode, ×1/×1, filter off ---
    // Lowest power: 2.7 µA at 1 Hz. Suitable for continuous weather logging.
    bmp.configure(BMP280Full::OSRS_X1, BMP280Full::OSRS_X1,
                   BMP280Full::MODE_FORCED, BMP280Full::FILTER_OFF,
                   BMP280Full::T_SB_0_5_MS);   // Configure ADC, (osrs_t 0–5, osrs_p 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → None

    Serial.println("WEATHER-MONITORING  T[C]   P[hPa]   ALT[m]");
}

unsigned long start_ms = 0;

void loop() {
    if (start_ms == 0) start_ms = millis();

    float t = bmp.temperature();                // Read temperature, () → f32 C
    float p = bmp.pressure();                  // Read pressure, () → f32 hPa
    float a = bmp.altitude();                  // Compute altitude, (sea_level_hpa=1013.25) → f32 m
                                                // uses default sea-level pressure of 1013.25 hPa

    Serial.print((millis() - start_ms) / 1000.0, 1);
    Serial.print("s   ");
    Serial.print(t, 2);  Serial.print("   ");
    Serial.print(p, 2);  Serial.print("   ");
    Serial.println(a, 2);

    delay(1000);
}