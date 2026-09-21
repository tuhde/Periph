#include <cstdio>
#include <cstdlib>
#include <gpiod.h>
#include <unistd.h>
#include "SiPoConnectionLinux.h"
#include "TPIC6B595.h"

static gpiod_line* get_output_line(const char* chip_path, unsigned int offset,
                                   const char* consumer, int default_value) {
    gpiod_chip* chip = ::gpiod_chip_open(chip_path);
    if (!chip) {
        std::perror("gpiod_chip_open");
        std::exit(2);
    }
    gpiod_line* line = ::gpiod_line_get(chip, offset);
    if (!line) {
        std::perror("gpiod_line_get");
        std::exit(2);
    }
    if (::gpiod_line_request_output(line, consumer, default_value) < 0) {
        std::perror("gpiod_line_request_output");
        std::exit(2);
    }
    return line;
}

int main() {
    const char* chip = getenv("GPIO_CHIP");
    if (!chip) chip = "/dev/gpiochip0";

    int rck    = getenv("SIPO_RCK")    ? atoi(getenv("SIPO_RCK"))    : 5;
    int srclr  = getenv("SIPO_SRCLR")  ? atoi(getenv("SIPO_SRCLR"))  : 6;
    int g      = getenv("SIPO_G")      ? atoi(getenv("SIPO_G"))      : 13;
    int ser_in = getenv("SIPO_SER_IN") ? atoi(getenv("SIPO_SER_IN")) : 19;
    int srck   = getenv("SIPO_SRCK")   ? atoi(getenv("SIPO_SRCK"))   : 26;

    gpiod_line* ser_in_line = get_output_line(chip, ser_in, "tpic6b595_si", 0); // SER IN output
    gpiod_line* srck_line   = get_output_line(chip, srck,   "tpic6b595_ck", 0); // SRCK output
    gpiod_line* rck_line    = get_output_line(chip, rck,    "tpic6b595_rc", 0); // RCK output
    gpiod_line* srclr_line  = (srclr >= 0) ? get_output_line(chip, srclr, "tpic6b595_clr", 1) : nullptr;
    gpiod_line* g_line      = (g >= 0)     ? get_output_line(chip, g,     "tpic6b595_g",   0) : nullptr;

    SiPoConnectionLinux connection(ser_in_line, srck_line, rck_line, srclr_line, g_line); // Create SiPo connection, (ser_in, srck, rck, srclr, g)
    TPIC6B595Minimal<SiPoConnectionLinux> chip(connection);                                // Create TPIC6B595 driver, (connection, num_devices=1)
                                                                                            // initialises every output to OFF (shadow zeroed, latched once)

    auto p0 = chip.pin(0);                                                                  // Get pin proxy, (n=0) → IOExpanderPin
    auto p7 = chip.pin(7);                                                                  // Get pin proxy, (n=7) → IOExpanderPin

    while (true) {
        p0.high();                                                                          // Set DMOS output ON, () → void
        p7.low();                                                                           // Set DMOS output OFF, () → void
        usleep(500000);
        p0.low();                                                                           // Set DMOS output OFF, () → void
        p7.high();                                                                          // Set DMOS output ON, () → void
        usleep(500000);
    }
    return 0;
}
