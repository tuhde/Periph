#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "DHTxxTransportZephyr.h"
#include "DHT11.h"

static const struct gpio_dt_spec dht_spec = GPIO_DT_SPEC_GET(DT_NODELABEL(gpio0), gpios);

DHTxxTransportZephyr transport(dht_spec);
DHT11Full dht(transport, 3);                    // Create DHT11 driver, (transport, max_retries=3)

const char* comfort(float h) {
    if (h < 30.0f) return "dry";
    if (h > 60.0f) return "humid";
    return "comfortable";
}

int main(void) {
    // --- Indoor comfort monitor ---
    // Reads temperature and humidity every 5 seconds and prints a one-line
    // status with a comfort assessment. Demonstrates reliable real-world
    // polling with retry-based error recovery.
    while (1) {
        float t, h;
        bool ok = dht.read_retry(3, t, h);      // Read with retries, (max_retries 1..255, t out, h out) → bool ok
        if (!ok) {
            // --- Handle read failure ---
            // After all retries are exhausted, log a warning and continue.
            // The next loop iteration will try again with a fresh sample.
            printk("WARN: DHT11 read failed after retries\n");
        } else {
            printk("%d.%d C, %d.%d %%RH, %s\n",
                   (int)t, (int)(t * 10) % 10,
                   (int)h, (int)(h * 10) % 10,
                   comfort(h));
        }
        k_sleep(K_SECONDS(5));
    }
    return 0;
}
