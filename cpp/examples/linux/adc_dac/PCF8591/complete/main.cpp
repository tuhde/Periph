#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "PCF8591.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x48;
    I2CConnectionLinux connection(bus, addr);

    PCF8591Full pcf(connection);                                            // Create PCF8591 driver, (connection)

    for (int ch = 0; ch < 4; ch++)
        printf("ch%d=%u\n", ch, pcf.read_channel(ch));                    // Read ADC channel, (channel 0–3) → uint8_t
                                                                           // 8-bit code; the first byte after a channel switch is discarded
    uint8_t buf[4];
    pcf.read_all(buf);                                                     // Read all four channels, (out[4]) → void
                                                                           // one auto-increment burst
    printf("all: %u %u %u %u\n", buf[0], buf[1], buf[2], buf[3]);

    float v0 = pcf.read_channel_voltage(0, 3.3f, 0.0f);                    // Read channel in volts, (channel, vref V, vagnd V) → float V
                                                                           // V = vagnd + code × (vref − vagnd) / 256
    float volts[4];
    pcf.read_all_voltage(volts, 3.3f, 0.0f);                               // Read all channels in volts, (out[4], vref V, vagnd V) → void
    printf("ch0=%.3f V, all: %.3f %.3f %.3f %.3f V\n", (double)v0,
           (double)volts[0], (double)volts[1], (double)volts[2], (double)volts[3]);

    pcf.configure(PCF8591Full::MODE_3_DIFFERENTIAL, false, true);          // Configure input mode, (input_mode 0–3, auto_increment, dac_enabled) → void
                                                                           // AIN0–AIN2 each measured against AIN3
    int8_t diff = pcf.read_differential(0);                                // Read differential channel, (channel) → int8_t
                                                                           // two's-complement code, −128…127
    printf("diff0=%d\n", diff);
    pcf.configure(PCF8591Full::MODE_4_SINGLE_ENDED, false, true);          // Back to four single-ended inputs

    pcf.set_dac(128);                                                      // Set DAC code, (value 0–255) → void
                                                                           // enables AOUT and writes the 8-bit code
    pcf.set_dac_voltage(0.25f);                                            // Set DAC as fraction of full scale, (fraction 0.0–1.0) → void
    pcf.disable_dac();                                                     // Disable AOUT, () → void
                                                                           // AOUT goes high-impedance; saves the internal oscillator current
    return 0;
}
