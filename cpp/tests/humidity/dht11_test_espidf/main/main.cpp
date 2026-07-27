#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "esp_timer.h"
#include "DHTxxConnectionESPIDF.h"
#include "DHT11.h"

static const gpio_num_t DHT11_DATA_PIN = GPIO_NUM_4;

static int passed = 0, failed = 0;
static void check_true(bool c, const char *l) {
    if (c) { printf("PASS %s\n", l); passed++; }
    else   { printf("FAIL %s\n", l); failed++; }
}

extern "C" void app_main(void) {
    DHTxxConnectionESPIDF connection(DHT11_DATA_PIN);
    DHT11Full dht(connection, 3);

    float t, h;
    bool ok = dht.read_retry(3, t, h);
    check_true(ok, "read_retry");
    check_true(t >= 0.0f && t <= 50.0f, "temp_range");
    check_true(h >= 20.0f && h <= 90.0f, "hum_range");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
