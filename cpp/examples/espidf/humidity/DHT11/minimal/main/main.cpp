#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "esp_timer.h"
#include "DHTxxTransportESPIDF.h"
#include "DHT11.h"

static const gpio_num_t DHT11_DATA_PIN = GPIO_NUM_4;

extern "C" void app_main(void) {
    DHTxxTransportESPIDF transport(DHT11_DATA_PIN);
    DHT11Minimal dht(transport);                                   // Create DHT11 driver, (transport)

    while (1) {
        float t, h;
        dht.read(t, h);                                            // Read temperature & humidity, (t°C out, h%RH out) → bool ok
        printf("%.1f C, %.1f %%RH\n", t, h);
        vTaskDelay(pdMS_TO_TICKS(2000));
    }
}
