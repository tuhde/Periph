#include <zephyr/kernel.h>
#include <zephyr/devicetree.h>
#include "DHT11PinZephyr.h"
#include "DHT11.h"

#define DHT11_NODE DT_ALIAS(dht11)

static const struct gpio_dt_spec dht11_spec = GPIO_DT_SPEC_GET(DHT11_NODE, gpios);

int main(void) {
    DHT11PinZephyr pin(dht11_spec);                      // Wrap gpio_dt_spec, (gpio_dt_spec) → DHT11PinZephyr
    DHT11Minimal<DHT11PinZephyr> dht(pin);               // Create DHT11 driver, (pin) → DHT11Minimal

    while (1) {
        float t, h;
        if (dht.read(t, h)) {                            // Read temperature and humidity, () → (float, float) °C, %RH
            printk("T: %.1f C  H: %.1f %%RH\n", (double)t, (double)h);
        } else {
            printk("read failed\n");
        }
        k_sleep(K_SECONDS(2));
    }
    return 0;
}
