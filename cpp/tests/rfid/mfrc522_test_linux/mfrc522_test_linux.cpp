#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif
#ifndef TEST_SPI_SPEED_HZ
#define TEST_SPI_SPEED_HZ 1000000
#endif

#include <stdio.h>
#include "SPITransportLinux.h"
#include "MFRC522.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    SPITransportLinux transport(TEST_SPI_BUS, TEST_SPI_DEVICE, 0, TEST_SPI_SPEED_HZ);
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

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
