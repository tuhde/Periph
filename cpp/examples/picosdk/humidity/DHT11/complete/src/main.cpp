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
    DHT11Full dht(connection, 3);                                   // Create DHT11 driver, (connection, max_retries=3)

    while (1) {
        float t = dht.read_temperature();                          // Read temperature, () → float °C
                                                                   // returns a fresh conversion each call
        float h = dht.read_humidity();                             // Read humidity, () → float %RH
                                                                   // returns a fresh conversion each call
        float t2, h2;
        bool ok = dht.read_retry(5, t2, h2);                      // Read with retries, (max_retries 1..255, t out, h out) → bool ok
                                                                   // retries up to 5 times on checksum error
        uint8_t raw[5];
        bool rok = dht.read_raw_with_retry(raw);                   // Read raw frame, (out[5]) → bool ok
                                                                   // returns the validated 5-byte frame
        printf("t=%.1f h=%.1f retry_ok=%d raw[0]=0x%02X\n",
               t, h, ok, raw[0]);
        sleep_ms(2000);
    }
    return 0;
}
