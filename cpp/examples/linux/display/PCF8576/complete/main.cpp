#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "PCF8576.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x38;
    I2CConnectionLinux connection(bus, addr);

    PCF8576Full pcf(connection);                                            // Create PCF8576 driver, (connection)

    pcf.set_mode(PCF8576Full::BACKPLANES_4, PCF8576Full::BIAS_1_3);        // Set multiplex and bias, (backplanes 1–4, bias) → void
                                                                           // 1:4 multiplex with 1/3 bias suits most 4-digit glass
    pcf.device_select(0);                                                  // Select cascaded device, (subaddress 0–7) → void
                                                                           // only matters when several PCF8576 share the bus
    pcf.enable();                                                          // Turn the display on, () → void
    for (uint8_t pos = 0; pos < 4; pos++)
        pcf.set_digit_7seg(pos, PCF8576Full::SEVEN_SEG[8]);                // Write one 7-segment digit, (position, segments) → void
                                                                           // SEVEN_SEG[8] lights every segment
    usleep(1000000);

    pcf.set_blink(PCF8576Full::BLINK_2_HZ);                                // Set blink frequency, (frequency, alternate_bank=false) → void
                                                                           // whole display blinks at 2 Hz
    usleep(2000000);
    pcf.set_blink(PCF8576Full::BLINK_OFF);

    const uint8_t raw[2] = {0x7F, 0x06};
    pcf.write_raw(0, raw, sizeof(raw));                                    // Write raw display RAM, (address, data, len) → void
                                                                           // one byte per digit position, bit = segment
    pcf.set_bank(PCF8576Full::BANK_0, PCF8576Full::BANK_0);                // Select RAM banks, (input_bank, output_bank) → void
                                                                           // banks only exist in static and 1:2 modes
    usleep(1000000);
    pcf.clear();                                                           // Clear display RAM, () → void
    pcf.disable();                                                         // Turn the display off, () → void
    return 0;
}
