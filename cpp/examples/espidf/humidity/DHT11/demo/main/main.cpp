#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "esp_timer.h"
#include "DHTxxTransportESPIDF.h"
#include "DHT11.h"

static const gpio_num_t DHT11_DATA_PIN = GPIO_NUM_4;

static const char* comfort(float h) {
    if (h < 30.0f) return "dry";
    if (h > 60.0f) return "humid";
    return "comfortable";
}

extern "C" void app_main(void) {
    DHTxxTransportESPIDF transport(DHT11_DATA_PIN);
    DHT11Full dht(transport, 3);                                   // Create DHT11 driver, (transport, max_retries=3)

    // --- Indoor comfort monitor ---
    // Reads temperature and humidity every 5 seconds and prints a one-line
    // status with a comfort assessment. Demonstrates reliable real-world polling
    // with retry-based error recovery.
    while (1) {
        float t, h;
        bool ok = dht.read_retry(3, t, h);                        // Read with retries, (max_retries 1..255, t out, h out) → bool ok
        if (!ok) {
            // --- Handle read failure ---
            // After all retries are exhausted, log a warning and continue.
            // The next loop iteration will try again with a fresh sample.
            printf("WARN: DHT11 read failed after retries\n");
        } else {
            printf("%.1f C, %.1f %%RH, %s\n", t, h, comfort(h));
        }
        vTaskDelay(pdMS_TO_TICKS(5000));
    }
}
