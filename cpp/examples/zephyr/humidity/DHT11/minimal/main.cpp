#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "DHTxxTransportZephyr.h"
#include "DHT11.h"

#ifndef DHT11_GPIO_NODE
#define DHT11_GPIO_NODE DT_NODELABEL(gpio0)
#endif
#ifndef DHT11_GPIO_PIN
#define DHT11_GPIO_PIN 4
#endif

static const struct gpio_dt_spec dht_spec = GPIO_DT_SPEC_GET(DT_NODELABEL(gpio0), gpios);

DHTxxTransportZephyr transport(dht_spec);
DHT11Minimal dht(transport);                    // Create DHT11 driver, (transport)

int main(void) {
    while (1) {
        float t, h;
        dht.read(t, h);                         // Read temperature & humidity, (t°C out, h%RH out) → bool ok
        printk("%d.%d C, %d.%d %%RH\n", (int)t, (int)(t * 10) % 10, (int)h, (int)(h * 10) % 10);
        k_sleep(K_SECONDS(2));
    }
    return 0;
}
