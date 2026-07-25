#include <Wire.h>
#include "I2CTransport.h"
#include "AHT21.h"

I2CTransport transport(Wire, 0x38);
AHT21Full aht(transport);                                              // Create AHT21 driver, (transport, addr=0x38) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();

    // --- Verify calibration before starting the logging session ---
    // Most AHT21 modules ship pre-calibrated; if the CAL bit is not set
    // the driver already sent the calibration init sequence during construction.
    Serial.print("Calibrated: ");
    Serial.println(aht.is_calibrated());                               // Check calibration status, () → bool

    Serial.println("Time     T (C)      RH (%)     Dew (C)");
}

void loop() {
    // --- Each reading requires an 80 ms measurement cycle ---
    // The sensor cannot output data faster than this; the driver
    // handles the trigger + wait internally.
    float t, h;
    bool crc_ok = aht.read_with_crc(t, h);                             // Read with CRC verification, (temperature_c, humidity_pct) → bool
    if (!crc_ok) {
        Serial.println("CRC error");
        delay(5000);
        return;
    }

    // --- Magnus formula dew-point approximation ---
    // gamma = ln(RH/100) + (17.625 * T) / (243.04 + T)
    // dew_point = (243.04 * gamma) / (17.625 - gamma)
    // Accurate to ±0.5 °C for 0 < T < 60 °C and 1 < RH < 100 %RH.
    float gamma = log(h / 100.0f) + (17.625f * t) / (243.04f + t);
    float dew   = (243.04f * gamma) / (17.625f - gamma);

    static unsigned long n = 0;
    Serial.print(n);           Serial.print("       ");
    Serial.print(t, 2);        Serial.print("     ");
    Serial.print(h, 2);        Serial.print("     ");
    Serial.println(dew, 2);
    n++;

    delay(5000);
}
