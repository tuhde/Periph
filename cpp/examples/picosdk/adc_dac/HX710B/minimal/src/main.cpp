#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "HX711ConnectionPicoSDK.h"
#include "HX710B.h"

// HX710B bit-bang pins: DOUT on GP2, PD_SCK on GP3.
HX711ConnectionPicoSDK connection(/*dout=*/2, /*pd_sck=*/3);
HX710BMinimal<HX711ConnectionPicoSDK> chip(connection);

int main(void) {
    stdio_init_all();
    while (true) {
        bool ready = chip.is_ready();
        int32_t raw = chip.read_raw();
        printf("%d\n", raw);
        sleep_ms(500);
    }
    return 0;
}
