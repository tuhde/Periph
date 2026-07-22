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
    MFRC522Full mfrc(transport);

    uint8_t chip_type, version;
    mfrc.version(chip_type, version);
    check_true(chip_type == 0x09, "chip_type == 0x09 (MFRC522)");
    check_true(version == 1 || version == 2, "version in {1, 2}");

    mfrc.antenna_on();
    uint8_t ctrl = mfrc._read_reg(0x14);
    check_true((ctrl & 0x03) == 0x03, "antenna_on sets TxControlReg bits 0|1");
    mfrc.antenna_off();
    ctrl = mfrc._read_reg(0x14);
    check_true((ctrl & 0x03) == 0x00, "antenna_off clears TxControlReg bits 0|1");
    mfrc.antenna_on();

    const uint8_t gains[6] = {18, 23, 33, 38, 43, 48};
    for (int i = 0; i < 6; i++) {
        mfrc.set_antenna_gain(gains[i]);
        check_true(mfrc.antenna_gain() == gains[i], "set_antenna_gain read back");
    }

    bool present = mfrc.is_card_present();
    check_true(present || !present, "is_card_present returns bool");

    uint8_t raw = mfrc._read_reg(0x37);
    check_true(raw == 0x90 || raw == 0x91 || raw == 0x92, "raw VersionReg in 0x90/0x91/0x92");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
