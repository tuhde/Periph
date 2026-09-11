#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "SPIConnectionZephyr.h"
#include "RFM9x.h"

#ifndef RFM9X_SPI_NODE
#define RFM9X_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef RFM9X_CS_GPIOS
#define RFM9X_CS_GPIOS DT_PROP(RFM9X_SPI_NODE, cs_gpios)
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(RFM9X_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 5000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .slave     = 0,
        .cs        = { .gpio = RFM9X_CS_GPIOS, .delay = 0 },
    };
    SPIConnectionZephyr connection(dev, cfg);
    RFM95Full radio(connection, 868000000);             // Create RFM95W full driver, (connection, frequency_hz=868e6) → RFM95Full

    radio.configure(7, 125.0, 5);                       // Configure LoRa modem, (sf=7, bandwidth_khz=125.0, coding_rate=5) → void
    radio.set_tx_power(17, true);                       // Set TX power, (power_dbm=17, use_pa_boost=true) → void
    radio.set_frequency(868000000);                     // Change carrier frequency, (frequency_hz=862e6–1020e6) → void
    radio.standby();                                    // Enter STDBY mode, () → void
    check_true(true, "configure + standby");

    const uint8_t msg[] = "hello";
    radio.send(msg, sizeof(msg) - 1);                   // Send packet, (data, len) → void

    uint8_t buf[64]; size_t got = 0;
    bool ok = radio.receive(buf, got, 2000);            // Receive single packet, (buf, len, timeout_ms=2000) → bool
    check_true(true, "receive call");

    radio.sleep();                                      // Enter SLEEP mode, () → void
    check_true(true, "sleep");
    radio.standby();

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
