#include <cstdio>
#include <gpiod.h>
#include "HX711ConnectionLinux.h"
#include "HX710B.h"

int main() {
    const char* chip_path = getenv("GPIO_CHIP") ? getenv("GPIO_CHIP") : "/dev/gpiochip0";
    int dout_line  = getenv("DOUT_LINE")   ? atoi(getenv("DOUT_LINE"))   : 5;
    int pd_sck_line = getenv("PD_SCK_LINE") ? atoi(getenv("PD_SCK_LINE")) : 6;

    struct gpiod_chip* chip_dev = gpiod_chip_open(chip_path);
    if (!chip_dev) { perror("gpiod_chip_open"); return 1; }
    struct gpiod_line* dout   = gpiod_chip_get_line(chip_dev, dout_line);
    struct gpiod_line* pd_sck = gpiod_chip_get_line(chip_dev, pd_sck_line);
    gpiod_line_request_input(dout,   "hx710b");
    gpiod_line_request_output(pd_sck, "hx710b", 0);
    HX711ConnectionLinux connection(dout, pd_sck);

    HX710BFull<HX711ConnectionLinux> chip(connection);                      // Create HX710B driver, (connection)

    bool ready = chip.is_ready();                                           // Check if conversion is ready (non-blocking), () → bool
                                                                             // returns true when DOUT is LOW
    printf("ready=%d\n", ready);
    int32_t raw = chip.read_raw();                                          // Read signed 24-bit differential-input value, () → int32_t
                                                                             // blocks until DOUT goes LOW, then clocks out 24 bits
    printf("raw=%d\n", raw);

    chip.set_rate(40);                                                      // Select differential-input output rate, (rate: 10|40) → void
                                                                             // takes effect after next read; issues dummy read to apply
    chip.set_rate(10);                                                      // (restores default 10 SPS)

    int32_t avg = chip.read_average(10);                                    // Average multiple raw readings, (times=10) → int32_t
                                                                             // blocks for `times` complete conversions
    printf("avg10=%d\n", avg);

    chip.tare(10);                                                          // Capture zero offset from 10-reading average, (times=10) → void
                                                                             // stores result in internal _offset; call with nothing on the scale
    int32_t offset = chip.get_offset();                                     // Return stored tare offset, () → int32_t
    printf("offset=%d\n", offset);

    chip.set_scale(420.0f);                                                 // Set calibration scale factor, (factor: float) → void
                                                                             // factor = (read_average() - offset) / known_weight_in_target_unit
    float scale = chip.get_scale();                                         // Return current scale factor, () → float
    printf("scale=%.4f\n", scale);

    float weight = chip.read_weight(5);                                     // Return calibrated weight, (times=1) → float
                                                                             // computes (read_average(times) - offset) / scale
    printf("weight=%.4f\n", weight);

    int32_t supp_raw = chip.read_supply_diff_raw();                         // Read raw DVDD−AVDD supply-difference code, () → int32_t
                                                                             // uncalibrated ADC code, no absolute LSB-to-volts scale
    printf("supp_raw=%d\n", supp_raw);

    chip.power_down();                                                      // Enter power-down mode, () → void
                                                                             // holds PD_SCK HIGH for >60 µs
    usleep(1000);
    chip.power_up();                                                        // Exit power-down, reset chip, discard settling conversion, () → void
                                                                             // resets to differential input, gain 128, 10 SPS
    return 0;
}
