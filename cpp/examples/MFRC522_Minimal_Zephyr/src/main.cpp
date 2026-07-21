#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "SPITransportZephyr.h"
#include "MFRC522.h"

#ifndef MFRC522_SPI_NODE
#define MFRC522_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef MFRC522_CS_GPIOS
#define MFRC522_CS_GPIOS DT_PROP(MFRC522_SPI_NODE, cs_gpios)
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(MFRC522_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 1000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .slave     = 0,
        .cs        = { .gpio = MFRC522_CS_GPIOS, .delay = 0 },
    };
    SPITransportZephyr transport(dev, cfg);
    MFRC522Minimal mfrc(transport);                                 // Create MFRC522 driver, (transport, bus_type=BUS_SPI)

    for (int i = 0; i < 10; i++) {
        bool present = mfrc.is_card_present();                     // Detect card in field, () → bool
        uint8_t uid[10];
        size_t  uid_len = 0;
        bool ok = mfrc.read_uid(uid, uid_len);                     // Read card UID (REQA → anticollision → HLTA), (out, len) → bool
        printk("present=%d uid_len=%d\n", present ? 1 : 0, (int)uid_len);
        k_sleep(K_MSEC(500));
    }

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
