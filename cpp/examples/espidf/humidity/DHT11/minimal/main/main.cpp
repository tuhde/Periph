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
    DHT11Minimal dht(connection);                                   // Create DHT11 driver, (connection)

    while (1) {
        float t, h;
        dht.read(t, h);                                            // Read temperature & humidity, (t°C out, h%RH out) → bool ok
        printf("%.1f C, %.1f %%RH\n", t, h);
        vTaskDelay(pdMS_TO_TICKS(2000));
    }
}
