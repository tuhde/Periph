#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "HX711TransportPicoSDK.h"
#include "HX711.h"

// HX711 bit-bang pins: DOUT on GP2, PD_SCK on GP3.
HX711TransportPicoSDK transport(/*dout=*/2, /*pd_sck=*/3);
HX711Minimal chip(transport);

int main(void) {

    stdio_init_all();
    while (true) {

    bool ready = chip.is_ready();
    int32_t raw = chip.read_raw();
    printf("%d\n", raw);
    sleep_ms(500);
        sleep_ms(10);
    }

    return 0;
}
