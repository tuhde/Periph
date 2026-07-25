#include <Wire.h>
#include "I2CTransport.h"
#include "PCF8591.h"

I2CTransport transport(Wire, 0x48);
PCF8591Full adc(transport);

const float VREF  = 3.3f;
const float VAGND = 0.0f;

void setup() {
    Serial.begin(115200);
    Wire.begin();
    adc.configure(PCF8591Full::MODE_4_SINGLE_ENDED, false, true);       // Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → None
                                                                           // single-ended mode with DAC output enabled
}

void loop() {
    // --- Wire a potentiometer across VAGND–VREF with the wiper to AIN0 ---
    // Connect an LED (with series resistor) to AOUT. In a loop, read AIN0, map
    // the 0–255 value to a DAC output value, and write it to AOUT — the LED
    // brightness tracks the potentiometer. This demonstrates the ADC→DAC
    // feedback path inside a single chip.
    for (int n = 0; n < 20; n++) {
        uint8_t raw = adc.read_channel(0);                                // Read single channel, (channel=0–3) → uint8_t
        float vin  = VAGND + (float)raw * (VREF - VAGND) / 256.0f;
        adc.set_dac(raw);                                                 // Enable DAC and set raw value, (value=0–255) → None
        float vout = VAGND + (float)raw * (VREF - VAGND) / 256.0f;
        Serial.print("n=");     Serial.print(n);
        Serial.print(" raw=");  Serial.print(raw);
        Serial.print(" vin=");  Serial.print(vin, 3);
        Serial.print("V  vout="); Serial.print(vout, 3);
        Serial.println("V");
        delay(200);
    }
}
