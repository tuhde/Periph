#include <stdio.h>
#include "pico/stdlib.h"
#include "hardware/gpio.h"
#include "DHTxxConnectionPicoSDK.h"
#include "DHT11.h"

static const uint DHT11_DATA_PIN = 4;

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    DHTxxConnectionPicoSDK connection(DHT11_DATA_PIN);
    DHT11Minimal dht(connection);                                   // Create DHT11 driver, (connection)

    while (1) {
        float t, h;
        dht.read(t, h);                                            // Read temperature & humidity, (t°C out, h%RH out) → bool ok
        printf("%.1f C, %.1f %%RH\n", t, h);
        sleep_ms(2000);
    }
    return 0;
}
