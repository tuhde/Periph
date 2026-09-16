#include <stdio.h>
#include <math.h>
#include <cstdlib>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "HX711ConnectionPicoSDK.h"
#include "HX710B.h"

// HX710B bit-bang pins: DOUT on GP2, PD_SCK on GP3.
HX711ConnectionPicoSDK connection(/*dout=*/2, /*pd_sck=*/3);
HX710BFull<HX711ConnectionPicoSDK> chip(connection);

// Battery-powered load cell demo: tare at startup, then print weight
// continuously, watching the DVDD−AVDD supply-difference reading for
// drift that signals a low battery.
static const float SCALE_FACTOR = 420.0f;
static const int32_t LOW_BATT_DELTA = 50000;

int main(void) {
    static float prev_weight = -999999.0f;
    static uint16_t iteration = 0;
    static int32_t baseline_supp_diff = 0;

    stdio_init_all();
    sleep_ms(2000);

    printf("Taring — keep scale empty...\n");
    chip.tare(10);                                         // Capture zero offset from 10-reading average, (times=10) → void
    chip.set_scale(SCALE_FACTOR);                          // Set calibration scale factor, (factor: float) → void
    printf("Tare done. Place weight on scale.\n");

    // Capture the supply-difference baseline at full charge — uncalibrated
    // ADC code useful only for relative drift tracking against a known-good
    // baseline, not an absolute voltage reading.
    baseline_supp_diff = chip.read_supply_diff_raw();      // Read raw DVDD−AVDD supply-difference code, () → int32_t

    while (true) {
        float weight = chip.read_weight(3);                // Return calibrated weight, (times=3) → float
        float rounded = (float)((int)(weight * 10.0f + 0.5f)) / 10.0f;
        if (abs(rounded - prev_weight) > 1.0f) {
            printf("-> %.1f g\n", (double)rounded);
            prev_weight = rounded;
        }
        if (iteration % 20 == 0) {
            // Sample the DVDD−AVDD supply-difference channel every ~10 s:
            // uncalibrated ADC code for relative drift tracking against a
            // known-good baseline (the datasheet's stated purpose for this
            // channel in battery-powered weigh-scale applications).
            int32_t supp_diff = chip.read_supply_diff_raw();// Read raw DVDD−AVDD supply-difference code, () → int32_t
            int32_t drift = supp_diff - baseline_supp_diff;
            if (abs(drift) > LOW_BATT_DELTA) {
                printf("LOW BATTERY (supply_diff=%d, drift=%d)\n", (int)supp_diff, (int)drift);
            }
        }
        iteration++;
        sleep_ms(500);
    }
    return 0;
}
