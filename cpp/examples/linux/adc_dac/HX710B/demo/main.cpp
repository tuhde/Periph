#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <gpiod.h>
#include "HX711ConnectionLinux.h"
#include "HX710B.h"

// Battery-powered load cell demo: tare at startup, then print weight
// continuously, watching the DVDD−AVDD supply-difference reading for
// drift that signals a low battery. Replace SCALE_FACTOR with the value
// calibrated for your load cell and wiring topology. Calibration: (1)
// call tare() with nothing on the scale; (2) place a known 100 g reference
// weight; (3) SCALE_FACTOR = (read_average() - get_offset()) / 100.
static const float SCALE_FACTOR = 420.0f;
static const int32_t LOW_BATT_DELTA = 50000;  // supply-diff drift threshold from baseline

int main() {
    const char* chip_path = getenv("GPIO_CHIP") ? getenv("GPIO_CHIP") : "/dev/gpiochip0";
    int dout_line  = getenv("DOUT_LINE")   ? atoi(getenv("DOUT_LINE"))   : 5;
    int pd_sck_line = getenv("PD_SCK_LINE") ? atoi(getenv("PD_SCK_LINE")) : 6;

    struct gpiod_chip* chip_dev = gpiod_chip_open(chip_path);
    if (!chip_dev) { perror("gpiod_chip_open"); return 1; }
    struct gpiod_line* dout   = gpiod_chip_get_line(chip_dev, dout_line);
    struct gpiod_line* pd_sck = gpiod_chip_get_line(chip_dev, pd_sck_line);
    gpiod_line_request_input(dout,   "hx710b");
    gpiod_line_request_output(pd_sck, "hx710b", 0);
    HX711ConnectionLinux connection(dout, pd_sck);

    HX710BFull<HX711ConnectionLinux> chip(connection);                      // Create HX710B driver, (connection)

    // --- Tare the scale before use ---
    // Averaging 10 readings with nothing on the scale suppresses noise in
    // the zero-offset capture, so later weight readings aren't skewed by drift.
    printf("Taring - keep scale empty...\n");
    chip.tare(10);                                                          // Capture zero offset from 10-reading average, (times=10) → void
    chip.set_scale(SCALE_FACTOR);                                           // Set calibration scale factor, (factor: float) → void
    printf("Tare done. Place weight on scale.\n");

    // --- Capture the supply-difference baseline at full charge ---
    // The datasheet gives no absolute LSB-to-volts conversion for this
    // channel — it is only useful for relative drift tracking. Capture one
    // reading at startup as a "known-good battery" baseline, then compare
    // later readings against it to detect discharge.
    int32_t baseline_supp_diff = chip.read_supply_diff_raw();              // Read raw DVDD−AVDD supply-difference code, () → int32_t

    float prev_weight = -999999.0f;
    uint16_t iteration = 0;
    while (true) {
        float weight = chip.read_weight(3);                                 // Return calibrated weight, (times=3) → float
        float rounded = roundf(weight * 10.0f) / 10.0f;
        if (fabsf(rounded - prev_weight) > 1.0f) {
            printf("-> %.1f g\n", rounded);
            prev_weight = rounded;
        }

        if (iteration % 20 == 0) {
            // --- Sample the DVDD−AVDD supply-difference channel every ~10 s ---
            // Uncalibrated ADC code, intended only for relative drift tracking
            // against a known-good baseline (the datasheet's stated purpose for
            // this channel in battery-powered weigh-scale applications) — not
            // an absolute voltage reading.
            int32_t supp_diff = chip.read_supply_diff_raw();                // Read raw DVDD−AVDD supply-difference code, () → int32_t
            int32_t drift = supp_diff - baseline_supp_diff;
            if (abs(drift) > LOW_BATT_DELTA) {
                printf("LOW BATTERY (supply_diff=%d, drift=%d)\n", supp_diff, drift);
            }
        }

        iteration++;
        usleep(500000);
    }
    return 0;
}
