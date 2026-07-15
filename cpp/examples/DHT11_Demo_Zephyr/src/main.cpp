#include <zephyr/kernel.h>
#include <zephyr/devicetree.h>
#include "DHT11PinZephyr.h"
#include "DHT11.h"

#define DHT11_NODE DT_ALIAS(dht11)

static const struct gpio_dt_spec dht11_spec = GPIO_DT_SPEC_GET(DHT11_NODE, gpios);

int main(void) {
    DHT11PinZephyr pin(dht11_spec);                      // Wrap gpio_dt_spec, (gpio_dt_spec) → DHT11PinZephyr
    DHT11Full<DHT11PinZephyr> dht(pin);                   // Create DHT11 driver, (pin) → DHT11Full

    // --- Indoor comfort monitor ---
    // Poll the sensor every 5 seconds and print a one-line status with a
    // comfort assessment. read_retry() recovers from occasional checksum
    // errors caused by timing jitter.
    while (1) {
        float t = 0.0f, h = 0.0f;
        bool ok = dht.read_retry(t, h, 3);               // Read with retry, (t, h, max_retries=3) → bool
        const char* comfort = (h < 30.0f) ? "dry" : ((h <= 60.0f) ? "comfortable" : "humid");
        if (ok) {
            printk("T=%.1f C  H=%.1f %%RH  (%s)\n", (double)t, (double)h, comfort);
        } else {
            printk("read failed after 3 attempts\n");
        }
        k_sleep(K_SECONDS(5));
    }
    return 0;
}
