#include <Periph.h>

// Battery-powered load cell demo: tare at startup, then print weight
// continuously, watching the DVDD−AVDD supply-difference reading for
// drift that signals a low battery. Replace SCALE_FACTOR with the value
// calibrated for your load cell and wiring topology. Calibration: (1)
// call tare() with nothing on the scale; (2) place a known 100 g reference
// weight; (3) SCALE_FACTOR = (read_average() - get_offset()) / 100.
static const float SCALE_FACTOR = 420.0f;
static const int32_t LOW_BATT_DELTA = 50000;  // supply-diff drift threshold from baseline

HX711Connection connection(5, 6);
HX710BFull<HX711Connection> chip(connection);

static float prev_weight = -999999.0f;
static uint16_t iteration = 0;
static int32_t baseline_supp_diff = 0;

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

    // --- Capture the supply-difference baseline at full charge ---
    // The datasheet gives no absolute LSB-to-volts conversion for this
    // channel — it is only useful for relative drift tracking. Capture one
    // reading at startup as a "known-good battery" baseline, then compare
    // later readings against it to detect discharge.
    baseline_supp_diff = chip.read_supply_diff_raw();      // Read raw DVDD−AVDD supply-difference code, () → int32_t
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

    if (iteration % 20 == 0) {
        // --- Sample the DVDD−AVDD supply-difference channel every ~10 s ---
        // Uncalibrated ADC code, intended only for relative drift tracking
        // against a known-good baseline (the datasheet's stated purpose for
        // this channel in battery-powered weigh-scale applications) — not
        // an absolute voltage reading.
        int32_t supp_diff = chip.read_supply_diff_raw();   // Read raw DVDD−AVDD supply-difference code, () → int32_t
        int32_t drift = supp_diff - baseline_supp_diff;
        if (abs(drift) > LOW_BATT_DELTA) {
            Serial.print("LOW BATTERY (supply_diff=");
            Serial.print(supp_diff);
            Serial.print(", drift=");
            Serial.print(drift);
            Serial.println(")");
        }
    }

    iteration++;
    delay(500);
}
