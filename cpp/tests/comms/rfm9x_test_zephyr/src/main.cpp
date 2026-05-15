#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <stdio.h>
#include "SpiTransportZephyr.h"
#include "RFM9x.h"

#ifndef RFM9X_SPI_NODE
#define RFM9X_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef RFM9X_CS_PIN
#define RFM9X_CS_PIN 0
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool cond) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

static void check_eq(const char* label, uint8_t got, uint8_t expected) {
    if (got == expected) { printk("PASS %s\n", label); passed++; }
    else { printk("FAIL %s: got 0x%02X, expected 0x%02X\n", label, got, expected); failed++; }
}

int main(void) {
    const struct device* dev = DEVICE_DT_GET(RFM9X_SPI_NODE);
    if (!device_is_ready(dev)) {
        printk("SPI device not ready\n");
        return 1;
    }

    SpiTransportZephyr transport(dev, RFM9X_CS_PIN);
    RFM95Full rfm(transport, 868000000);

    uint8_t ver = rfm.version();
    check_eq("version_reg", ver, 0x12);
    check_true("version_nonzero", ver != 0);
    check_true("rssi_sane", rfm.rssi() > -150.0f && rfm.rssi() < 0.0f);

    rfm.send(reinterpret_cast<const uint8_t*>("test"), 4);
    k_sleep(K_MSEC(50));

    rfm.standby();
    rfm.sleep();
    rfm.standby();

    rfm.set_tx_power(14, false);
    rfm.set_tx_power(17, true);
    rfm.set_frequency(868000000);
    rfm.configure(7, 125, 5, true);
    check_true("configure_valid", true);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}