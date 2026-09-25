#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/gpio.h>
#include "DHTxxConnectionZephyr.h"
#include "DHT11.h"

#ifndef DHT11_GPIO_NODE
#define DHT11_GPIO_NODE DT_NODELABEL(gpio0)
#endif
#ifndef DHT11_GPIO_PIN
#define DHT11_GPIO_PIN 4
#endif

static const struct gpio_dt_spec dht_spec = { .port = DEVICE_DT_GET(DHT11_GPIO_NODE), .pin = DHT11_GPIO_PIN, .dt_flags = 0 };

DHTxxConnectionZephyr connection(dht_spec);
DHT11Full dht(connection, 3);                    // Create DHT11 driver, (connection, max_retries=3)

int main(void) {
    while (1) {
        float t = dht.read_temperature();       // Read temperature, () → float °C
                                               // returns a fresh conversion each call
        float h = dht.read_humidity();          // Read humidity, () → float %RH
                                               // returns a fresh conversion each call
        float t2, h2;
        bool ok = dht.read_retry(5, t2, h2);    // Read with retries, (max_retries 1..255, t out, h out) → bool ok
                                               // retries up to 5 times on checksum error
        uint8_t raw[5];
        bool rok = dht.read_raw_with_retry(raw);// Read raw frame, (out[5]) → bool ok
                                               // returns the validated 5-byte frame
        printk("t=%d.%d h=%d.%d retry_ok=%d raw[0]=0x%02X\n",
               (int)t, (int)(t * 10) % 10,
               (int)h, (int)(h * 10) % 10,
               ok, raw[0]);
        k_sleep(K_SECONDS(2));
    }
    return 0;
}
