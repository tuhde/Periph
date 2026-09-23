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

    HX711Minimal hx(connection);                                            // Create HX711 driver, (connection)

    while (true) {
        int32_t raw = hx.read_raw();                                       // Block until data ready and read, () → int32_t ADC counts
        printf("raw=%d\n", raw);
        usleep(100000);
    }
    return 0;
}
