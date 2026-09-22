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
    gpiod_line* srclr_line  = get_output_line(chip, srclr, "tpic6b595_clr", 1); // SRCLR output (idle HIGH)
    gpiod_line* g_line      = get_output_line(chip, g,     "tpic6b595_g",   0); // G output (idle LOW)

    SiPoConnectionLinux connection(ser_in_line, srck_line, rck_line, srclr_line, g_line); // Create SiPo connection, (ser_in, srck, rck, srclr, g)
    TPIC6B595Full<SiPoConnectionLinux> chip(connection, 2);                               // Create TPIC6B595 full driver, (connection, num_devices=2)
                                                                                            // two cascaded devices — 16 outputs total (DRAIN0..DRAIN15)

    auto p0 = chip.pin(0);                                                                 // Get pin proxy for DRAIN0 of device 0, (n=0) → IOExpanderPin

    // --- Pin-level control ---
    p0.high();                                                                              // Set DRAIN0 ON, () → void
                                                                                            // sets shadow[0] bit 0, reverses the cascade, shifts out and pulses RCK
    p0.low();                                                                               // Set DRAIN0 OFF, () → void
                                                                                            // clears shadow[0] bit 0, retransmits and latches
    p0.toggle();                                                                            // Invert shadow bit, () → void

    uint8_t state = p0.read();                                                              // Read pin state, () → uint8_t
                                                                                            // returns the shadow bit (no bus read — SiPo is write-only)
    p0.write(HIGH);                                                                         // Write pin high, (v=HIGH) → void
                                                                                            // equivalent to high(); updates shadow, retransmits, latches
    p0.set(true);                                                                           // OutputPin set, (high=true) → void
                                                                                            // same path as high()/write(HIGH), but matches the OutputPin contract

    // --- Port-level bulk write ---
    chip.write_port(0, 0xAA);                                                               // Write device 0 outputs, (port=0, mask=0xAA) → void
                                                                                            // sets DRAIN{1,3,5,7} ON, DRAIN{0,2,4,6} OFF; preserves device 1
    chip.write_port(1, 0x55);                                                               // Write device 1 outputs, (port=1, mask=0x55) → void

    // --- Bulk fill / off ---
    chip.fill(true);                                                                        // Set every output ON, (value=true) → void
                                                                                            // fills every shadow byte with 0xFF and retransmits — fast "all on" path
    chip.fill(false);                                                                       // Set every output OFF, (value=false) → void
                                                                                            // fills every shadow byte with 0x00 and retransmits — fast "all off" path
    chip.off();                                                                             // Turn every output off, () → void
                                                                                            // shorthand for fill(false); the safe initial state

    // --- Multi-device bulk write ---
    uint8_t bytes_[2] = { 0x01, 0x80 };
    chip.write_all(bytes_, 2);                                                              // Write all device bytes, (values=uint8_t*, len=2) → void
                                                                                            // updates both shadow bytes and performs one transmit + latch

    // --- Hardware features (Full only) ---
    chip.clear();                                                                           // Pulse SRCLR, () → void
                                                                                            // clears the shift register only; outputs unaffected until next RCK pulse
    chip.set_output_enable(false);                                                          // Force every output off via G, (enabled=false) → void
                                                                                            // drives G HIGH, blanking outputs without disturbing the shadow register
    usleep(100000);
    chip.set_output_enable(true);                                                           // Re-enable outputs, (enabled=true) → void
                                                                                            // drives G LOW; outputs resume from the previously-latched state

    return 0;
}
