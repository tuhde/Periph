#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "HX711TransportZephyr.h"
#include "HX711.h"

#define HX711_DOUT_NODE DT_ALIAS(hx711_dout)
#define HX711_SCK_NODE  DT_ALIAS(hx711_sck)

int main(void) {
    static const struct gpio_dt_spec dout   = GPIO_DT_SPEC_GET(HX711_DOUT_NODE, gpios);
    static const struct gpio_dt_spec pd_sck = GPIO_DT_SPEC_GET(HX711_SCK_NODE,  gpios);

    HX711TransportZephyr transport(dout, pd_sck);
    HX711Minimal<HX711TransportZephyr> chip(transport);

    while (1) {
        bool ready = chip.is_ready();
        int32_t raw = chip.read_raw();
        printk("%d\n", (int)raw);
        k_sleep(K_MSEC(500));
    }
    return 0;
}
