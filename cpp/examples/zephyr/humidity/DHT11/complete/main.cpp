#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "DHTxxTransportZephyr.h"
#include "DHT11.h"

static const struct gpio_dt_spec dht_spec = GPIO_DT_SPEC_GET(DT_NODELABEL(gpio0), gpios);

DHTxxTransportZephyr transport(dht_spec);
DHT11Full dht(transport, 3);                    // Create DHT11 driver, (transport, max_retries=3)

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
