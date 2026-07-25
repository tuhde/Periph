#include <cstdio>
#include <gpiod.h>
#include "HX711TransportLinux.h"
#include "HX711.h"

int main() {
    const char* chip_path = getenv("GPIO_CHIP") ? getenv("GPIO_CHIP") : "/dev/gpiochip0";
    int dout_line  = getenv("DOUT_LINE")   ? atoi(getenv("DOUT_LINE"))   : 5;
    int pd_sck_line = getenv("PD_SCK_LINE") ? atoi(getenv("PD_SCK_LINE")) : 6;

    struct gpiod_chip* chip_dev = gpiod_chip_open(chip_path);
    if (!chip_dev) { perror("gpiod_chip_open"); return 1; }
    struct gpiod_line* dout   = gpiod_chip_get_line(chip_dev, dout_line);
    struct gpiod_line* pd_sck = gpiod_chip_get_line(chip_dev, pd_sck_line);
    gpiod_line_request_input(dout,   "hx711");
    gpiod_line_request_output(pd_sck, "hx711", 0);
    HX711TransportLinux transport(dout, pd_sck);

    HX711Minimal hx(transport);                                            // Create HX711 driver, (transport)

    while (true) {
        int32_t raw = hx.read_raw();                                       // Block until data ready and read, () → int32_t ADC counts
        printf("raw=%d\n", raw);
        usleep(100000);
    }
    return 0;
}
