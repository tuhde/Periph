#include <zephyr/kernel.h>
#include <zephyr/devicetree.h>
#include "DHT11PinZephyr.h"
#include "DHT11.h"

#define DHT11_NODE DT_ALIAS(dht11)

static const struct gpio_dt_spec dht11_spec = GPIO_DT_SPEC_GET(DHT11_NODE, gpios);

int main(void) {
    DHT11PinZephyr pin(dht11_spec);                      // Wrap gpio_dt_spec, (gpio_dt_spec) → DHT11PinZephyr
    DHT11Full<DHT11PinZephyr> dht(pin);                   // Create DHT11 driver, (pin) → DHT11Full

    while (1) {
        float t = 0.0f, h = 0.0f;
        bool ok = dht.read_retry(t, h, 3);               // Read with retry, (t, h, max_retries=3) → bool
        uint8_t raw[5];
        bool raw_ok = dht.read_raw(raw);                 // Read raw 5-byte frame, (b: uint8_t[5]) → bool
        printk("ok=%d t=%.1f h=%.1f", ok, (double)t, (double)h);
        if (raw_ok) {
            printk(" raw=[%02X %02X %02X %02X %02X]",
                   raw[0], raw[1], raw[2], raw[3], raw[4]);
        }
        printk("\n");
        k_sleep(K_SECONDS(2));
    }
    return 0;
}
