#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "INA219.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define INA219_ADDR 0x40

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, INA219_ADDR);
    INA219Full ina(transport);

    float v_readings[10];
    float i_readings[10];
    float p_readings[10];

    for (int n = 0; n < 10; n++) {
        float v = ina.voltage();
        float i = ina.current();
        float p = ina.power();
        v_readings[n] = v;
        i_readings[n] = i;
        p_readings[n] = p;

        printk("V=%.3fV  I=%.4fA  P=%.4fW\n", (double)v, (double)i, (double)p);

        if (n == 3) {
            printk("--- switch on load now ---\n");
        }
        k_sleep(K_SECONDS(1));
    }

    float v_min = v_readings[0], v_max = v_readings[0], v_sum = 0.0f;
    float i_min = i_readings[0], i_max = i_readings[0], i_sum = 0.0f;
    float p_min = p_readings[0], p_max = p_readings[0], p_sum = 0.0f;
    for (int n = 0; n < 10; n++) {
        if (v_readings[n] < v_min) v_min = v_readings[n];
        if (v_readings[n] > v_max) v_max = v_readings[n];
        v_sum += v_readings[n];
        if (i_readings[n] < i_min) i_min = i_readings[n];
        if (i_readings[n] > i_max) i_max = i_readings[n];
        i_sum += i_readings[n];
        if (p_readings[n] < p_min) p_min = p_readings[n];
        if (p_readings[n] > p_max) p_max = p_readings[n];
        p_sum += p_readings[n];
    }
    printk("V min=%.4f max=%.4f mean=%.4f\n", (double)v_min, (double)v_max, (double)(v_sum / 10.0f));
    printk("I min=%.4f max=%.4f mean=%.4f\n", (double)i_min, (double)i_max, (double)(i_sum / 10.0f));
    printk("P min=%.4f max=%.4f mean=%.4f\n", (double)p_min, (double)p_max, (double)(p_sum / 10.0f));

    return 0;
}
