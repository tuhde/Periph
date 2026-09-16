#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "ADE7953.h"

#ifndef ADE7953_I2C_NODE
#define ADE7953_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef ADE7953_ADDR
#define ADE7953_ADDR 0x38
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(ADE7953_I2C_NODE);
    I2CConnectionZephyr connection(dev, ADE7953_ADDR);
    ADE7953Full ade(connection, 251.0f, 30.0f);                    // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

    printk("version: 0x%02X\n", ade.version());                    // Read silicon version, () → 0x..
                                                                      // returns the silicon revision
    printk("V=%.2f\n",   ade.voltage());                            // Read bus voltage, () → V
                                                                      // converts raw VRMS to volts using voltage_gain
    printk("I_a=%.3f\n", ade.current());                            // Read load current, () → A
                                                                      // converts raw IRMSA to amperes using current_gain
    printk("P_a=%.2f\n", ade.activePower());                        // Read active power, () → W
                                                                      // converts raw AWATT (instantaneous, 6.99 kHz) to watts
    printk("E_a=%.4f\n", ade.activeEnergy());                       // Read active energy, () → Wh
                                                                      // converts raw AENERGYA accumulated LSBs to watt-hours
    printk("PF=%.3f\n",  ade.powerFactor());                        // Read power factor, () → ratio
                                                                      // converts raw PFA (1 LSB = 2^-15) to a −1.0..+1.0 ratio
    printk("f=%.2f\n",    ade.lineFrequency());                      // Read line frequency, () → Hz

    ade.configureChannelB(30.0f);                                    // Set Channel B calibration, (current_gain_b) → none
    printk("I_b=%.3f\n", ade.currentB());                            // Read Current Channel B, () → A

    ade.setActiveEnergyMode('a', 0);                                 // Set active-energy mode A, (channel, mode) → none
                                                                      // mode = 0 (normal) | 1 (positive-only) | 2 (absolute)
    ade.setPga('a', 1);                                              // Write PGA Channel A, (channel, gain) → none
                                                                      // gain 1, 2, 4, 8, 16 (+22 valid only for Channel A)
    ade.setPhaseCalibration('a', 0.0f);                              // Write phase calibration A, (channel, delay_s) → none
    ade.setGainCalibration(0x282, 0x400000);    // Write active-power gain A, (reg, value) → none
                                                                      // 0x400000 = unity; valid range 0x200000..0x600000
    ade.setOffsetCalibration(0x289, 0);       // Write active-power offset A, (reg, value) → none
                                                                      // signed 24-bit offset
    printk("checksum: 0x%08X\n", (unsigned)ade.checksum());         // Read CRC/checksum, () → u32
    ade.enableChecksum(true);                                        // Enable CRC/checksum, (enabled) → none

    ade.configureOvervoltage(260.0f);                                // Configure overvoltage, (threshold) → none
                                                                      // threshold in volts (same scale as voltage())
    ade.configureOvercurrent(40.0f);                                 // Configure overcurrent, (threshold) → none
                                                                      // threshold in amperes; applies to BOTH current channels

    ade.reset();                                                     // Software reset, () → none
                                                                      // waits 110 ms then re-runs the mandatory power-up sequence
    return 0;
}