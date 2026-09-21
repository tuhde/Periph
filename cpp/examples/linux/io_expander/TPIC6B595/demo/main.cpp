// TPIC6B595 demo — "knight rider" chase pattern across two cascaded devices.
//
// Hardware:
//   Two cascaded TPIC6B595s driving 16 LEDs as an automotive-cluster-style
//   indicator bank (DRAIN0..DRAIN7 on each device). Each LED's anode goes to
//   the supply through a series resistor and its cathode to a DRAIN pin;
//   writing HIGH turns the LED on (active-low via the DMOS sink).
//
// The demo walks a single lit LED back and forth across all 16 outputs and,
// every few sweeps, blanks every output for half a second via
// set_output_enable(false) to demonstrate glitch-free global blanking. The
// shadow register is untouched across the blank, so the chase pattern resumes
// exactly where it left off.
#include <cstdio>
#include <cstdlib>
#include <gpiod.h>
#include <unistd.h>
#include "SiPoConnectionLinux.h"
#include "TPIC6B595.h"

static constexpr uint8_t NUM_DEVICES = 2;
static constexpr uint8_t NUM_OUTPUTS = NUM_DEVICES * 8;

static gpiod_line* get_output_line(const char* chip_path, unsigned int offset,
                                   const char* consumer, int default_value) {
    gpiod_chip* c = ::gpiod_chip_open(chip_path);
    if (!c) { std::perror("gpiod_chip_open"); std::exit(2); }
    gpiod_line* line = ::gpiod_line_get(c, offset);
    if (!line) { std::perror("gpiod_line_get"); std::exit(2); }
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
    gpiod_line* srclr_line  = get_output_line(chip, srclr, "tpic6b595_clr", 1); // SRCLR output (idle HIGH)
    gpiod_line* g_line      = get_output_line(chip, g,     "tpic6b595_g",   0); // G output (idle LOW)

    SiPoConnectionLinux connection(ser_in_line, srck_line, rck_line, srclr_line, g_line); // Create SiPo connection, (ser_in, srck, rck, srclr, g)
    TPIC6B595Full<SiPoConnectionLinux> chip(connection, NUM_DEVICES);                     // Create TPIC6B595 full driver, (connection, num_devices=2)
                                                                                            // two cascaded devices — 16 outputs total; outputs start OFF

    int8_t position = 0;
    int8_t direction = 1;
    uint8_t sweep_count = 0;
    constexpr uint8_t  BLANK_EVERY = 3;
    constexpr useconds_t BLANK_US = 500000;

    while (true) {
        // --- Walk a single lit LED across all 16 outputs and back ---
        // Use write_all() each step so both cascaded devices latch together —
        // there is no way to update just one downstream device without re-sending
        // the whole chain's data.
        uint8_t bytes_[NUM_DEVICES] = {0, 0};
        uint8_t port = position / 8;
        uint8_t bit  = position % 8;
        bytes_[port] = (uint8_t)(1u << bit);
        chip.write_all(bytes_, NUM_DEVICES);                                                // Write all device bytes, (values=uint8_t*, len=2) → void

        std::printf("position=%2d  bytes=[0x%02X, 0x%02X]\n",
                    (int)position, bytes_[0], bytes_[1]);

        // --- Periodically blank every output via G, then resume ---
        // set_output_enable(false) drives G HIGH, forcing every DMOS off without
        // touching the shadow register — the LEDs simply resume exactly where they
        // left off when G is re-enabled.
        sweep_count++;
        if (sweep_count % BLANK_EVERY == 0) {
            chip.set_output_enable(false);                                                   // Force every output off via G, (enabled=false) → void
                                                                                              // the chase pattern's shadow state is preserved
            std::printf("  blanked via G for %u us\n", (unsigned)BLANK_US);
            usleep(BLANK_US);
            chip.set_output_enable(true);                                                    // Re-enable outputs, (enabled=true) → void
                                                                                              // LEDs resume from the previously-latched state
        }

        // Bounce the chase position at both ends of the strip
        position += direction;
        if (position >= (int8_t)(NUM_OUTPUTS - 1) || position <= 0) {
            direction = -direction;
            usleep(100000);
        } else {
            usleep(80000);
        }
    }
    return 0;
}
