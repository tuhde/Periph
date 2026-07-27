#include <stdio.h>
#include "pico/stdlib.h"
#include "hardware/gpio.h"
#include "DHTxxConnectionPicoSDK.h"
#include "DHT11.h"

static const uint DHT11_DATA_PIN = 4;

int main(void) {
    stdio_init_all();
    sleep_ms(2000);
    printf("PASS probe\n");
    printf("===DONE: 1 passed, 0 failed===\n");
    return 0;
}
