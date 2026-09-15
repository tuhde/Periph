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

int main(void) {
    const struct device *dev = DEVICE_DT_GET(RFM9X_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 5000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .slave     = 0,
        .cs        = { .gpio = RFM9X_CS_GPIOS, .delay = 0 },
    };
    SPIConnectionZephyr connection(dev, cfg);
    RFM95Minimal radio(connection, 868000000);          // Create RFM95W driver, (connection, frequency_hz=868e6) → RFM95Minimal

    const uint8_t msg[] = "hello";
    radio.send(msg, sizeof(msg) - 1);                   // Send packet, (data, len) → void
    printk("sent\n");
    return 0;
}
