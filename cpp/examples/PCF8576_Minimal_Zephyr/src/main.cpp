#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "PCF8576.h"

#ifndef PCF8576_I2C_NODE
#define PCF8576_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef PCF8576_ADDR
#define PCF8576_ADDR 0x38
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(PCF8576_I2C_NODE);
    I2CTransportZephyr transport(dev, PCF8576_ADDR);
    PCF8576Minimal lcd(transport);                       // Create PCF8576 driver, (transport)

    static const uint8_t digits[] = {1, 2, 3, 4};
    for (uint8_t i = 0; i < 4; i++) {
        uint8_t seg = PCF8576Minimal::SEVEN_SEG[digits[i]];  // Encode 7-segment digit, (digit 0–9) → uint8_t
        lcd.set_digit_7seg(i, seg);                      // Write one digit, (position 0–19, segments 0–255) → void
    }

    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
