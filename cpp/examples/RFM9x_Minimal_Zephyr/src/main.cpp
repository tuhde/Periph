#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/spi.h>
#include <stdio.h>
#include "SpiTransportZephyr.h"
#include "RFM9x.h"

#ifndef RFM9X_SPI_NODE
#define RFM9X_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef RFM9X_CS_PIN
#define RFM9X_CS_PIN 0
#endif

int main(void) {
    const struct device* dev = DEVICE_DT_GET(RFM9X_SPI_NODE);
    if (!device_is_ready(dev)) {
        printf("SPI device not ready\n");
        return 1;
    }

    SpiTransportZephyr transport(dev, RFM9X_CS_PIN);
    RFM95Minimal rfm(transport, 868000000);             // Create RFM95 driver, (transport, frequency_hz=868 MHz)

    uint8_t ver = rfm.version();                        // Read silicon revision, () → uint8_t
    printf("version: 0x%02X\n", ver);

    rfm.send(reinterpret_cast<const uint8_t*>("Hello"), 5);  // Transmit packet, (data, len) → void
    printf("sent\n");

    rfm.standby();                                      // Enter STANDBY mode, () → void

    rfm.sleep();                                        // Enter SLEEP mode, () → void

    printf("===DONE: 1 passed, 0 failed===\n");
    return 0;
}