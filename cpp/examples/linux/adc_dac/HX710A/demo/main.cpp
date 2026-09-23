#include <cstdio>
#include <unistd.h>
#include <cstdlib>
#include <cmath>
#include "HX711ConnectionLinux.h"
#include "HX710A.h"

// Temperature-monitored load cell demo: tare at startup, then print weight
// continuously, sampling the on-chip temperature sensor periodically.
// Replace SCALE_FACTOR with the value calibrated for your load cell and V_DD.
// Calibration: (1) call tare() with nothing on the scale; (2) place a known
// 100 g reference weight; (3) SCALE_FACTOR = (read_average() - get_offset()) / 100.
static const float SCALE_FACTOR = 420.0f;

int main() {
    const char* chip_path = getenv("GPIO_CHIP") ? getenv("GPIO_CHIP") : "/dev/gpiochip0";
    int dout_line  = getenv("DOUT_LINE")   ? atoi(getenv("DOUT_LINE"))   : 5;
    int pd_sck_line = getenv("PD_SCK_LINE") ? atoi(getenv("PD_SCK_LINE")) : 6;

    HX711ConnectionLinux connection(chip_path, (unsigned)dout_line, (unsigned)pd_sck_line);  // Request DOUT/PD_SCK via libgpiod v2, (chip_path, dout_line, pd_sck_line)

    HX710AFull<HX711ConnectionLinux> chip(connection);                      // Create HX710A driver, (connection)

    // --- Tare the scale before use ---
    // Averaging 10 readings with nothing on the scale suppresses noise in
    // the zero-offset capture, so later weight readings aren't skewed by drift.
    printf("Taring - keep scale empty...\n");
    chip.tare(10);                                                          // Capture zero offset from 10-reading average, (times=10) → void
    chip.set_scale(SCALE_FACTOR);                                           // Set calibration scale factor, (factor: float) → void
    printf("Tare done. Place weight on scale.\n");

    float prev_weight = -999999.0f;
    uint16_t iteration = 0;
    while (true) {
        float weight = chip.read_weight(3);                                 // Return calibrated weight, (times=3) → float
        float rounded = roundf(weight * 10.0f) / 10.0f;
        if (fabsf(rounded - prev_weight) > 1.0f) {
            printf("-> %.1f g\n", rounded);
            prev_weight = rounded;
        }

        if (iteration % 10 == 0) {
            // --- Sample the on-chip temperature sensor every ~5 s ---
            // This is an uncalibrated raw ADC code (~20.4 LSB/°C, chip-to-chip
            // offset/gain vary per the datasheet), intended only for the
            // datasheet's stated purpose of relative drift compensation of the
            // weight reading — not as an absolute °C measurement.
            int32_t temp_raw = chip.read_temperature_raw();                 // Read raw on-chip temperature code, () → int32_t
            printf("temp raw=%d\n", temp_raw);
        }

        iteration++;
        usleep(500000);
    }
    return 0;
}
