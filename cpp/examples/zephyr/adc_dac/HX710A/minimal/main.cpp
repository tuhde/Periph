#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "HX711ConnectionZephyr.h"
#include "HX710A.h"

#define HX710A_DOUT_NODE DT_ALIAS(hx710a_dout)
#define HX710A_SCK_NODE  DT_ALIAS(hx710a_sck)

int main(void) {
    static const struct gpio_dt_spec dout   = GPIO_DT_SPEC_GET(HX710A_DOUT_NODE, gpios);
    static const struct gpio_dt_spec pd_sck = GPIO_DT_SPEC_GET(HX710A_SCK_NODE,  gpios);

    HX711ConnectionZephyr connection(dout, pd_sck);
    HX710AMinimal<HX711ConnectionZephyr> chip(connection);

    while (1) {
        bool ready = chip.is_ready();
        int32_t raw = chip.read_raw();
        printk("%d\n", (int)raw);
        k_sleep(K_MSEC(500));
    }
    return 0;
}
