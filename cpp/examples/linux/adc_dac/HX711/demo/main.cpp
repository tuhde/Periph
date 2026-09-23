#include <cstdio>
#include <unistd.h>
#include <cstdlib>
#include "HX711ConnectionLinux.h"
#include "HX711.h"

int main() {
    const char* chip_path = getenv("GPIO_CHIP") ? getenv("GPIO_CHIP") : "/dev/gpiochip0";
    int dout_line  = getenv("DOUT_LINE")   ? atoi(getenv("DOUT_LINE"))   : 5;
    int pd_sck_line = getenv("PD_SCK_LINE") ? atoi(getenv("PD_SCK_LINE")) : 6;

    HX711ConnectionLinux connection(chip_path, (unsigned)dout_line, (unsigned)pd_sck_line);  // Request DOUT/PD_SCK via libgpiod v2, (chip_path, dout_line, pd_sck_line)

    HX711Full hx(connection);                                               // Create HX711 driver, (connection)

    // --- Kitchen scale with tare and continuous readout ---
    // Tares with the platform empty, then prints the weight in grams.
    // Calibrate once with a known mass: scale = (read_average() - offset) / mass_g.
    printf("Taring - keep the scale empty...\n");
    hx.tare(10);                                                           // Capture the zero offset, (times=10) → void
    hx.set_scale(2280.0f);                                                 // Set calibration factor, (factor counts per gram) → void
    printf("Tared. Place mass...\n");
    while (true) {
        float g = hx.read_weight(5);                                       // Calibrated weight, (times=5) → float g
        printf("%.1f g\n", (double)g);
        usleep(200000);
    }
    return 0;
}
