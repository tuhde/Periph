#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "HX711TransportPicoSDK.h"
#include "HX711.h"

// HX711 bit-bang pins: DOUT on GP2, PD_SCK on GP3.
HX711TransportPicoSDK transport(/*dout=*/2, /*pd_sck=*/3);
HX711Full chip(transport);

int main(void) {
    static const float SCALE_FACTOR = 420.0f;
    static float prev_weight = -999999.0f;

    stdio_init_all();
    sleep_ms(2000);

    printf("Taring — keep scale empty...\n");
    chip.tare(10);                                         // Capture zero offset from 10-reading average, (times=10) → void
    chip.set_scale(SCALE_FACTOR);                          // Set calibration scale factor, (factor: float) → void
    printf("Tare done. Place weight on scale.\n");
    while (true) {

    float weight = chip.read_weight(3);                    // Return calibrated weight, (times=3) → float
    float rounded = (float)((int)(weight * 10.0f + 0.5f)) / 10.0f;
    if (abs(rounded - prev_weight) > 1.0f) {
        printf("-> ");
        printf("%.1f", rounded);
        printf(" g\n");
        prev_weight = rounded;
    }
    sleep_ms(500);
        sleep_ms(10);
    }

    return 0;
}
