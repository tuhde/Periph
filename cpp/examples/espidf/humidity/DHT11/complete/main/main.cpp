#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "esp_timer.h"
#include "DHTxxConnectionESPIDF.h"
#include "DHT11.h"

static const gpio_num_t DHT11_DATA_PIN = GPIO_NUM_4;

extern "C" void app_main(void) {
    DHTxxConnectionESPIDF connection(DHT11_DATA_PIN);
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
        vTaskDelay(pdMS_TO_TICKS(2000));
    }
}
