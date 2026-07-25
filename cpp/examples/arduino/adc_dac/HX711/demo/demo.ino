#include "HX711Transport.h"
#include "HX711.h"

// Kitchen scale demo: tare at startup, then print weight continuously.
// Replace SCALE_FACTOR with the value calibrated for your load cell and V_DD.
// Calibration: (1) call tare() with nothing on the scale; (2) place a known
// weight W grams; (3) SCALE_FACTOR = (read_average() - get_offset()) / W.
static const float SCALE_FACTOR = 420.0f;

HX711Transport transport(5, 6);
HX711Full<HX711Transport> chip(transport);

static float prev_weight = -999999.0f;

void setup() {
    Serial.begin(115200);
    delay(2000);

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
    delay(500);
}
