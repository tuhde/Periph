#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/gpio.h>
#include "DHTxxTransportZephyr.h"
#include "DHT11.h"

#ifndef DHT11_DATA_PIN
#define DHT11_DATA_PIN 4
#endif

int main(void) {
    const struct gpio_dt_spec spec = GPIO_DT_SPEC_GET_OR(DT_NODELABEL(gpio0), DHT11_DATA_PIN, {0});
    DHTxxTransportZephyr transport(&spec);
    DHT11Full dht(transport);

    float temp, hum;
    dht.readRetry(temp, hum, 3);

    const char* comfort;
    if (hum < 30) {
        comfort = "dry";
    } else if (hum <= 60) {
        comfort = "comfortable";
    } else {
        comfort = "humid";
    }

    printk("Temperature: %.1f C, Humidity: %.1f %%RH -- %s\n", temp, hum, comfort);
    return 0;
}
