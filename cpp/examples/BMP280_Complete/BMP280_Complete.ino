#include <Wire.h>
#include "I2CTransport.h"
#include "BMP280.h"

I2CTransport transport(Wire, 0x76);
BMP280Full bmp(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();

    Serial.println(bmp.chip_id());             // Read chip ID, () → int
                                                // confirms 0x58 = BMP280 present
    Serial.println(bmp.status());              // Read status register, () → int
                                                // checks measuring and im_update flags
    bmp.configure(BMP280Full::OSRS_X2, BMP280Full::OSRS_X4,
                   BMP280Full::MODE_FORCED, BMP280Full::FILTER_4,
                   BMP280Full::T_SB_62_5_MS);  // Configure ADC, (osrs_t 0–5, osrs_p 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → None
                                                // sets pressure ×4, temp ×2, forced mode, IIR coeff 4
    bmp.set_oversampling(BMP280Full::OSRS_X1, BMP280Full::OSRS_X1);  // Update oversampling, (osrs_t, osrs_p) → None
                                                                     // returns to lowest power (×1/×1)
    bmp.set_filter(BMP280Full::FILTER_OFF);    // Update IIR filter, (coeff 0–4) → None
                                               // disables filter for raw readings
    bmp.set_standby(BMP280Full::T_SB_250_MS);  // Update standby time, (t_sb 0–7) → None
                                               // only relevant in normal mode
    bmp.reset();                               // Soft reset and re-init, () → None
                                                // reloads calibration, re-applies config
}

void loop() {}