#include <SPI.h>
#include "SPIConnection.h"
#include "AD7705.h"

#define TEMP_COEFF 0.05f
#define TEMP_REFERENCE 1.25f
#define CHANGE_THRESHOLD 0.001f

#ifndef TEST_CS_PIN
#define TEST_CS_PIN 10
#endif

SPISettings settings(5000000, MSBFIRST, SPI_MODE3);
SPIConnection connection(SPI, TEST_CS_PIN, settings);
AD7705Full adc(connection, 2.5, AD7705Minimal::MCLK_2_4576MHZ);      // Construct and initialise the AD7705, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → AD7705Full

float last_pressure = 0.0f;
bool first = true;

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();

    // --- Configure both channels for the bridge-pressure application ---
    // Channel 1 reads the pressure bridge at gain 128 (small mV-level signal);
    // Channel 2 reads an auxiliary temperature sensor at gain 2 for temperature
    // compensation of the pressure reading.
    adc.configure(1, AD7705Full::GAIN_128, true, true, 50);          // Configure channel 1, (channel=1, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None
    adc.configure(2, AD7705Full::GAIN_2, true, false, 50);          // Configure channel 2, (channel=2, gain=GAIN_2, bipolar=true, buffered=false, output_rate_hz=50) → None

    // --- Self-calibrate both channels before the measurement loop ---
    adc.self_calibrate(1);                                           // Self-calibrate channel 1, (channel=1) → None
    adc.self_calibrate(2);                                           // Self-calibrate channel 2, (channel=2) → None
}

void loop() {
    // --- Sample continuously and compensate the pressure reading for temperature ---
    // pressure_compensated = pressure - TEMP_COEFF * (temp - TEMP_REFERENCE)
    // Print whenever the compensated reading changes by more than 1 mV.
    float pressure_raw = adc.read_voltage(1);                        // Read voltage on channel 1, (channel=1) → float V
    float temp = adc.read_voltage(2);                                // Read voltage on channel 2, (channel=2) → float V
    float pressure = pressure_raw - TEMP_COEFF * (temp - TEMP_REFERENCE);
    if (first || fabs(pressure - last_pressure) > CHANGE_THRESHOLD) {
        Serial.print("→ pressure="); Serial.print(pressure, 4);
        Serial.print(" V (raw "); Serial.print(pressure_raw, 4);
        Serial.print(" V, temp "); Serial.print(temp, 4);
        Serial.println(" V)");
        last_pressure = pressure;
        first = false;
    }
    delay(200);
}
