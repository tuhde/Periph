#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Pn532.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define PN532_ADDR 0x24

static void print_hex(const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; i++) printk("%02X", data[i]);
}

static volatile bool g_frame_ready = false;
static void on_frame() { g_frame_ready = true; }

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, PN532_ADDR);
    PN532Full reader(connection);                                  // Create PN532 driver, (connection) → void
    // The PN532 address is fixed at 0x24; I0 low and I1 high select the I²C interface.
    reader.init();                                                 // Wake, check firmware, SAM normal mode, finite retries, () → bool
    // GetFirmwareVersion (IC 0x32), SAMConfiguration normal mode, MxRtyPassiveActivation = 2.

    PN532FirmwareVersion fw;
    reader.firmware_version(fw);                                   // GetFirmwareVersion, (fw&) → bool
    // IC 0x32, firmware 1.6 on current parts; the support bits list the protocols.
    printk("IC 0x%02X firmware %u.%u support 0x%02X\n", fw.ic, fw.version, fw.revision, fw.support);
    PN532GeneralStatus gs;
    reader.general_status(gs);                                     // GetGeneralStatus, (status&) → bool
    // Last error code, external field detected, active targets, SAM status.
    printk("error 0x%02X field %u targets %u SAM 0x%02X\n", gs.error, (unsigned)gs.field, gs.targets, gs.sam_status);
    printk("self test %u\n", (unsigned)reader.self_test());       // Diagnose, (test=0x00) → bool
    // Communication line test: the chip echoes an 8-byte pattern.
    printk("ROM test %u\n", (unsigned)reader.self_test(0x01));    // Diagnose, (test=0x01) → bool
    // ROM checksum test.

    uint8_t uid[10];
    uint8_t len = reader.read_uid(uid);                            // Detect a type A card, (uid*, timeoutMs=1000) → uint8_t length
    // InListPassiveTarget at 106 kbps type A, then InRelease.
    printk("UID ");
    print_hex(uid, len);
    printk("\n");

    PN532Target t[1];
    int n = reader.list_passive_targets(t, PN532Full::BAUD_106_A, 1); // InListPassiveTarget, (targets*, baud=BAUD_106_A, maxTargets=1, initiatorData=nullptr, initLen=0, timeoutMs=1000) → int count
    // Keeps the target active; t[0].tg is the logical number for the next commands.
    if (n > 0) {
        printk("tg %u SENS_RES 0x%04X SEL_RES 0x%02X\n", t[0].tg, t[0].sens_res, t[0].sel_res);
        reader.deselect(t[0].tg);                                  // InDeselect, (target=0) → bool
        // Puts the card to sleep but keeps its information in the PN532.
        reader.select(t[0].tg);                                    // InSelect, (target=0) → bool
        // Wakes and reselects the deselected card.
        uint8_t block[16];
        if (t[0].uid_len == 7) {
            reader.ntag_read_page(t[0].tg, 4, block);              // NTAG READ, (target, page, out[16]) → bool
            // Returns pages 4..7 (16 bytes).
            const uint8_t page[4] = {0x01, 0x02, 0x03, 0x04};
            reader.ntag_write_page(t[0].tg, 4, page);              // NTAG WRITE, (target, page, data[4]) → bool
            // Writes one 4-byte user page.
        } else {
            const uint8_t key[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
            reader.mifare_authenticate(t[0].tg, 4, key, t[0].uid, t[0].uid_len); // MIFARE auth, (target, block, key[6], uid*, uidLen, keyB=false) → bool
            // Key A authentication of sector 1 with the transport key.
            reader.mifare_read_block(t[0].tg, 4, block);           // MIFARE READ, (target, block, out[16]) → bool
            // Reads block 4.
            reader.mifare_write_block(t[0].tg, 4, block);          // MIFARE WRITE, (target, block, data[16]) → bool
            // Writes the same 16 bytes back.
        }
        const uint8_t rd[2] = {0x30, 0x00};
        int m = reader.data_exchange(t[0].tg, rd, 2, block, sizeof(block)); // InDataExchange, (target, data*, len, out*, outMax) → int length
        // Raw READ of block/page 0 with PN532 framing and CRC.
        printk("data_exchange %d\n", m);
        reader.release(t[0].tg);                                   // InRelease, (target=0) → bool
        // Ends the session with the card.
    }

    reader.set_rf_field(false);                                    // RFConfiguration item 1, (on, autoRfca=false) → bool
    // RF field off; a card in the field loses power.
    reader.set_rf_field(true);                                     // RFConfiguration item 1, (on, autoRfca=false) → bool
    // RF field back on.
    reader.set_max_retries(2);                                     // RFConfiguration item 5, (passiveActivation=2, atr=0xFF, psl=1) → bool
    // 0xFF would retry forever.
    uint8_t txmode = 0;
    reader.read_register(0x6302, txmode);                          // ReadRegister, (address, value&) → bool
    // 0x6302 is CIU_TxMode in the PN532's SFR space.
    reader.write_register(0x6302, txmode);                         // WriteRegister, (address, value) → bool
    // Writes the value back unchanged.
    uint8_t resp[8];
    n = reader.send_command(0x02, nullptr, 0, resp, sizeof(resp)); // Raw command, (command, data*, len, out*, outMax, timeoutMs=1000) → int length
    // GetFirmwareVersion through the raw frame interface.
    printk("raw response %d bytes\n", n);
    const uint8_t reqa[1] = {0x26};
    n = reader.communicate_thru(reqa, 1, resp, sizeof(resp));      // InCommunicateThru, (data*, len, out*, outMax) → int length
    // Raw RF exchange with the current target; fails (-1, last_error()) without one.
    printk("communicate_thru %d (error %d)\n", n, reader.last_error());

    reader.start_command(0x02);                                    // Command without waiting, (command, data=nullptr, len=0) → bool
    // Writes the frame and reads the ACK only.
    printk("ready %u\n", (unsigned)reader.wait_ready(1000));      // Wait for a frame, (timeoutMs) → bool
    // Polls the status byte every 1 ms.
    printk("poll %u\n", (unsigned)reader.poll_interrupt());       // Frame ready?, () → bool
    // A single status-byte read.
    n = reader.read_response(0x02, resp, sizeof(resp));            // Read the pending response, (command, out*, outMax, timeoutMs=1000) → int length
    // Header, NACK, full frame, checksums.

    reader.on_interrupt(on_frame);                                 // Subscribe to IRQ, (callback, intPin=nullptr) → void
    // Falling edge on the connection's IRQ pin; without a pin nothing is delivered.
    reader.send_command(0x02, nullptr, 0, resp, sizeof(resp));     // Raw command, (command, data*, len, out*, outMax, timeoutMs=1000) → int length
    printk("irq seen %u\n", (unsigned)g_frame_ready);
    reader.off_interrupt();                                        // Unsubscribe, () → void
    // Removes the edge handler.

    reader.power_down();                                           // PowerDown, (wakeupSources=0x20) → bool
    // Sleeps until the next I²C access; the driver retries the wake-up write.
    return 0;
}
