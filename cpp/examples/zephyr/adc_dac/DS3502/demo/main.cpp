#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "DS3502.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, DS3502Minimal::I2C_ADDRESS);
    DS3502Full pot(connection, 15.0f, 0.0f);            // Create DS3502 Full driver, (connection, vHigh=1.0 V, vLow=0.0 V)
    // --- Power-up position ---
    // The constructor leaves the wiper where the IVR put it (factory default 0x40).
    uint8_t up = pot.getWiper();                        // Read wiper position, () → uint8_t 0–127
    printk("power-up wiper %d -> %.3f V\n", (int)up, (double)pot.getVoltage());

    // --- Set 6.0 V ---
    pot.setVoltage(6.0f);                               // Set RW output voltage, (volts V) → void
    float ratio = pot.getRatio();                       // Read divider ratio, () → float
    printk("code %d ratio %.3f voltage %.3f V\n", (int)pot.getWiper(), (double)ratio, (double)pot.getVoltage());

    // --- Sweep in 16 steps ---
    // The step size is (V_RH − V_RL)/127, about 118 mV at a 15 V span.
    for (int code = 0; code <= 127; code += 8) {
        pot.setWiper(code);                             // Set wiper position, (code 0–127) → void
        printk("code %d -> %.3f V\n", code, (double)pot.getVoltage());
        k_sleep(K_MSEC(200));
    }
    pot.setWiper(127);

    // --- Step up and down ---
    pot.setVoltage(6.0f);
    uint8_t up4 = pot.increment(4);                     // Step the wiper, (steps=1) → uint8_t 0–127
    uint8_t dn4 = pot.increment(-4);
    printk("+4 -> %d, -4 -> %d\n", (int)up4, (int)dn4);

    // --- MODE ---
    // MODE = 1 (the working mode) keeps wiper writes in SRAM; storeWiper switches
    // to MODE = 0 only for the duration of the EEPROM write.
    bool eeprom = pot.getMode();                        // Read MODE, () → bool
    printk("eeprom mode %d\n", (int)eeprom);
    pot.setMode(false);                                 // Select SRAM-only writes, (eeprom bool) → void

    // --- Persist the chosen code ---
    // This consumes one of the EEPROM's limited write cycles.
    int code = pot.getWiper();
    bool stored = pot.storeWiper(code);                 // Store power-up position, (code=-1) → bool
    bool ready = pot.waitReady();                       // Acknowledge polling, (timeoutMs=30) → bool
    printk("stored %d, ready %d, read back %d\n", (int)stored, (int)ready, (int)pot.getWiper());
    return 0;
}
