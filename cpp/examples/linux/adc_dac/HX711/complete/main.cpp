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
                                                                           // channel A, gain 128

    printf("ready=%d\n", hx.is_ready());                                  // Conversion ready (DOUT low)?, () → bool
    int32_t raw = hx.read_raw();                                           // Wait for data and read, () → int32_t ADC counts
    printf("raw=%d\n", raw);
    int32_t avg = hx.read_average(10);                                     // Average N readings, (times=10) → int32_t ADC counts
    printf("avg10=%d\n", avg);

    hx.tare(10);                                                           // Capture the zero offset, (times=10) → void
                                                                           // run with the scale empty
    printf("offset=%d\n", hx.get_offset());                               // Stored tare offset, () → int32_t ADC counts
    hx.set_scale(2280.0f);                                                 // Set calibration factor, (factor counts per unit) → void
                                                                           // calibrate: (read_average() − offset) / known mass
    printf("scale=%.1f weight=%.2f\n", (double)hx.get_scale(),            // Calibration factor, () → float
           (double)hx.read_weight(5));                                     // Calibrated weight, (times=1) → float in the scale's units

    hx.set_gain(32);                                                       // Select channel and gain, (gain 128/64 = channel A, 32 = channel B) → void
                                                                           // takes effect after the next conversion
    hx.power_down();                                                       // Enter power-down, () → void
                                                                           // PD_SCK held high for more than 60 µs
    usleep(1000);
    hx.power_up();                                                         // Exit power-down, () → void
                                                                           // resets to channel A / gain 128 and discards the settling sample
    return 0;
}
