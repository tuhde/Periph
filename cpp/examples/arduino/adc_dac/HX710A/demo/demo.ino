#include "HX711Connection.h"
#include "HX710A.h"

// Temperature-monitored load cell demo: tare at startup, then print weight
// continuously, sampling the on-chip temperature sensor periodically.
// Replace SCALE_FACTOR with the value calibrated for your load cell and V_DD.
// Calibration: (1) call tare() with nothing on the scale; (2) place a known
// 100 g reference weight; (3) SCALE_FACTOR = (read_average() - get_offset()) / 100.
static const float SCALE_FACTOR = 420.0f;

HX711Connection connection(5, 6);
HX710AFull<HX711Connection> chip(connection);

static float prev_weight = -999999.0f;
static uint16_t iteration = 0;

void setup() {
    Serial.begin(115200);
    delay(2000);

    // --- Tare the scale before use ---
    // Averaging 10 readings with nothing on the scale suppresses noise in
    // the zero-offset capture, so later weight readings aren't skewed by drift.
    Serial.println("Taring — keep scale empty...");
    chip.tare(10);                                         // Capture zero offset from 10-reading average, (times=10) → void
    chip.set_scale(SCALE_FACTOR);                          // Set calibration scale factor, (factor: float) → void
    Serial.println("Tare done. Place weight on scale.");
}

void loop() {
    float weight = chip.read_weight(3);                    // Return calibrated weight, (times=3) → float
    float rounded = (float)((int)(weight * 10.0f + 0.5f)) / 10.0f;
    if (abs(rounded - prev_weight) > 1.0f) {
        Serial.print("-> ");
        Serial.print(rounded, 1);
        Serial.println(" g");
        prev_weight = rounded;
    }

    if (iteration % 10 == 0) {
        // --- Sample the on-chip temperature sensor every ~5 s ---
        // This is an uncalibrated raw ADC code (~20.4 LSB/°C, chip-to-chip
        // offset/gain vary per the datasheet), intended only for the
        // datasheet's stated purpose of relative drift compensation of the
        // weight reading — not as an absolute °C measurement.
        int32_t temp_raw = chip.read_temperature_raw();    // Read raw on-chip temperature code, () → int32_t
        Serial.print("temp raw=");
        Serial.println(temp_raw);
    }

    iteration++;
    delay(500);
}
