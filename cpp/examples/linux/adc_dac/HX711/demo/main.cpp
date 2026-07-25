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

    HX711Full hx(transport);                                               // Create HX711 driver, (transport)

    // --- Scale with tare and continuous weight readout ---
    // Tares on the first reading then prints weight in grams.
    int32_t offset = hx.read_average(10);                                  // Average N readings for tare, (n=10) → int32_t
    hx.set_offset(offset);                                                 // Set tare offset, (offset) → void
    hx.set_scale(2280.0f);                                                 // Set scale factor, (scale) → void
                                                                           // calibrate: place known mass, scale = (avg - offset) / mass_g
    printf("Tared. Place mass...\n");
    while (true) {
        float g = hx.read_units_average(5);                                // Average N readings in units, (n=5) → float
        printf("%.1f g\n", g);
        usleep(200000);
    }
    return 0;
}
