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
    ADE7953Minimal ade(connection, 251.0f, 30.0f);                 // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

    for (int i = 0; i < 10; i++) {
        float v = ade.voltage();                                     // Read bus voltage, () → V
        float a = ade.current();                                     // Read load current, () → A
        float p = ade.activePower();                                 // Read active power, () → W
        float e = ade.activeEnergy();                                // Read active energy, () → Wh
        printk("V=%.2f A=%.3f P=%.2f E=%.4f\n", v, a, p, e);
        k_sleep(K_MSEC(1000));
    }
    return 0;
}