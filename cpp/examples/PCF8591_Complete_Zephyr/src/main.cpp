#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "PCF8591.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define PCF8591_ADDR 0x48

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, PCF8591_ADDR);
    PCF8591Full adc(transport);

    while (1) {
        uint8_t ch0_raw = adc.read_channel(0);                              // Read single channel, (channel=0–3) → uint8_t
                                                                              // discards the stale first conversion byte; returns 0–255
        uint8_t ch1_raw = adc.read_channel(1);                              // Read single channel, (channel=0–3) → uint8_t
                                                                              // selects channel 1 via the control byte, returns 0–255
        uint8_t all_raw[PCF8591Minimal::NUM_CHANNELS];
        adc.read_all(all_raw);                                               // Read all four channels, (out[4]) → None
                                                                              // sets AI=1 and reads 5 bytes; discards stale byte 0

        float v0 = adc.read_channel_voltage(0, 3.3f, 0.0f);                  // Read channel as voltage, (channel, vref=3.3 V, vagnd=0.0 V) → float V
                                                                              // converts raw to voltage using V_AGND + raw × (V_REF−V_AGND) / 256
        float v_all[PCF8591Minimal::NUM_CHANNELS];
        adc.read_all_voltage(v_all, 3.3f, 0.0f);                             // Read all channels as voltages, (out[4], vref=3.3 V, vagnd=0.0 V) → None
                                                                              // returns four voltages using the same conversion

        adc.configure(PCF8591Full::MODE_3_DIFFERENTIAL, false, false);      // Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → None
                                                                              // sets AIP=01 (3 differential channels vs AIN3) and clears AOE/AI
        int8_t diff = adc.read_differential(0);                              // Read differential channel, (channel=0–2) → int8_t
                                                                              // returns signed 8-bit two's complement (-128 to 127)
        adc.configure(PCF8591Full::MODE_4_SINGLE_ENDED, false, true);       // Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → None
                                                                              // restores 4 single-ended mode and enables the DAC output
        adc.set_dac(128);                                                    // Enable DAC and set raw value, (value=0–255) → None
                                                                              // sets AOE=1 and writes 128 to the DAC register; V_AOUT ≈ V_REF/2
        adc.set_dac_voltage(0.25f);                                          // Set DAC as fraction of (VREF−VAGND), (fraction=0.0–1.0) → None
                                                                              // maps fraction to 0–255 and writes the DAC; AOUT follows
        adc.disable_dac();                                                   // Disable DAC output, () → None
                                                                              // clears AOE; AOUT returns to high-impedance
        k_sleep(K_SECONDS(1));
    }
    return 0;
}
