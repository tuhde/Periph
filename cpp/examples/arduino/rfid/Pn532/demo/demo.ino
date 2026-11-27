#include <Wire.h>
#include <stdarg.h>
#include <stdio.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x24);
PN532Full reader(connection);                                  // Create PN532 driver, (connection) → void

static void periph_printf(const char* fmt, ...) {
    char buf[96];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    Serial.print(buf);
}

static void print_hex(const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; i++) periph_printf("%02X", data[i]);
}

static volatile bool g_frame_ready = false;
static void on_frame() { g_frame_ready = true; }

void setup() {
    Serial.begin(115200);
    Wire.begin();
    reader.init();                                                 // Wake, check firmware, SAM normal mode, finite retries, () → bool

    // --- Identity and health ---
    // IC 0x32 is the PN532; the support byte lists ISO 14443 A/B and ISO 18092.
    // The communication-line self test echoes a pattern through the firmware.
    PN532FirmwareVersion fw;
    reader.firmware_version(fw);                                   // GetFirmwareVersion, (fw&) → bool
    periph_printf("PN532 IC 0x%02X firmware %u.%u support 0x%02X\n", fw.ic, fw.version, fw.revision, fw.support);
    PN532GeneralStatus gs;
    reader.general_status(gs);                                     // GetGeneralStatus, (status&) → bool
    periph_printf("field %u targets %u\n", (unsigned)gs.field, gs.targets);
    periph_printf("self test %u\n", (unsigned)reader.self_test());       // Diagnose, (test=0x00) → bool

    // --- Card polling: 15 s at 1 Hz ---
    // Present a card or tag. The finite retry count makes every poll return
    // after a few milliseconds when the field is empty.
    PN532Target t[1];
    for (int i = 0; i < 15; i++) {
        int n = reader.list_passive_targets(t);                    // InListPassiveTarget, (targets*, baud=BAUD_106_A, maxTargets=1, ...) → int count
        if (n > 0) {
            periph_printf("%2d s UID ", i);
            print_hex(t[0].uid, t[0].uid_len);
            periph_printf(" SENS_RES 0x%04X SEL_RES 0x%02X\n", t[0].sens_res, t[0].sel_res);
            reader.release(t[0].tg);                               // InRelease, (target=0) → bool
        } else {
            periph_printf("%2d s no card\n", i);
        }
        delay(1000);
    }

    // --- Tag access ---
    // SEL_RES 0x00 is an NTAG / MIFARE Ultralight: read page 4 and write it back.
    // SEL_RES 0x08 is a MIFARE Classic 1K: authenticate sector 1 with the
    // transport key FF FF FF FF FF FF, read block 4 and write it back.
    // Writing sector trailers can lock a sector forever.
    if (reader.list_passive_targets(t) > 0) {                      // InListPassiveTarget, (targets*, ...) → int count
        uint8_t data[16];
        reader.deselect(t[0].tg);                                  // InDeselect, (target=0) → bool
        reader.select(t[0].tg);                                    // InSelect, (target=0) → bool
        if (t[0].sel_res == 0x00) {
            if (reader.ntag_read_page(t[0].tg, 4, data)) {         // NTAG READ, (target, page, out[16]) → bool
                periph_printf("pages 4..7 ");
                print_hex(data, 16);
                periph_printf("\n");
                reader.ntag_write_page(t[0].tg, 4, data);          // NTAG WRITE, (target, page, data[4]) → bool
            }
            const uint8_t rd[2] = {0x30, 0x00};
            int m = reader.data_exchange(t[0].tg, rd, 2, data, sizeof(data)); // InDataExchange, (target, data*, len, out*, outMax) → int length
            periph_printf("raw read %d bytes\n", m);
        } else if (t[0].sel_res & 0x08) {
            const uint8_t key[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
            if (reader.mifare_authenticate(t[0].tg, 4, key, t[0].uid, t[0].uid_len) && // MIFARE auth, (target, block, key[6], uid*, uidLen, keyB=false) → bool
                reader.mifare_read_block(t[0].tg, 4, data)) {      // MIFARE READ, (target, block, out[16]) → bool
                periph_printf("block 4 ");
                print_hex(data, 16);
                periph_printf("\n");
                reader.mifare_write_block(t[0].tg, 4, data);       // MIFARE WRITE, (target, block, data[16]) → bool
            } else {
                periph_printf("authentication failed (error %d)\n", reader.last_error());
            }
        }
        reader.release(t[0].tg);                                   // InRelease, (target=0) → bool
    } else {
        periph_printf("no card for the tag access part\n");
    }

    // --- Raw RF and raw commands ---
    // InCommunicateThru sends bytes without PN532 protocol handling; with no
    // target selected it reports an error. send_command reaches every command.
    uint8_t resp[16];
    const uint8_t rd0[2] = {0x30, 0x00};
    int n = reader.communicate_thru(rd0, 2, resp, sizeof(resp));   // InCommunicateThru, (data*, len, out*, outMax) → int length
    periph_printf("communicate_thru %d (error %d)\n", n, reader.last_error());
    n = reader.send_command(0x02, nullptr, 0, resp, sizeof(resp)); // Raw command, (command, data*, len, out*, outMax, timeoutMs=1000) → int length
    periph_printf("raw GetFirmwareVersion ");
    if (n > 0) print_hex(resp, (size_t)n);
    periph_printf("\n");
    uint8_t txmode = 0;
    reader.read_register(0x6302, txmode);                          // ReadRegister, (address, value&) → bool
    periph_printf("CIU_TxMode 0x%02X\n", txmode);
    reader.write_register(0x6302, txmode);                         // WriteRegister, (address, value) → bool

    // --- Wake on card with on_interrupt ---
    // Infinite activation retries make InListPassiveTarget wait for a card; the
    // IRQ pin (when wired to the connection) or the status byte signals the response.
    reader.set_max_retries(0xFF);                                  // RFConfiguration item 5, (passiveActivation=2, atr=0xFF, psl=1) → bool
    g_frame_ready = false;
    reader.on_interrupt(on_frame);                                 // Subscribe to IRQ, (callback, intPin=nullptr) → void
    const uint8_t list[2] = {0x01, 0x00};
    reader.start_command(0x4A, list, 2);                           // Command without waiting, (command, data*, len) → bool
    periph_printf("waiting 10 s for a card ...\n");
    for (int i = 0; i < 100 && !g_frame_ready; i++) {
        if (reader.poll_interrupt()) break;                        // Frame ready?, () → bool
        delay(100);
    }
    reader.off_interrupt();                                        // Unsubscribe, () → void
    if (reader.wait_ready(10)) {                                   // Wait for a frame, (timeoutMs) → bool
        n = reader.read_response(0x4A, resp, sizeof(resp));        // Read the pending response, (command, out*, outMax, timeoutMs=1000) → int length
        if (n >= 6) {
            periph_printf("card arrived, UID ");
            print_hex(resp + 6, resp[5]);
            periph_printf("\n");
        }
        reader.release();                                          // InRelease, (target=0) → bool
    } else {
        reader.read_response(0x4A, resp, sizeof(resp), 0);         // Read the pending response, (command, out*, outMax, timeoutMs) → int length
        periph_printf("no card arrived\n");                               // the timeout aborted the command with an ACK frame
    }
    reader.set_max_retries();                                      // RFConfiguration item 5, (passiveActivation=2, atr=0xFF, psl=1) → bool
    uint8_t uid[10];
    uint8_t len = reader.read_uid(uid);                            // Detect a type A card, (uid*, timeoutMs=1000) → uint8_t length
    periph_printf("read_uid %u bytes\n", len);

    // --- RF field off and on, then power down ---
    // With the field off a card loses power; the reader draws less current.
    reader.set_rf_field(false);                                    // RFConfiguration item 1, (on, autoRfca=false) → bool
    delay(1000);
    reader.set_rf_field(true);                                     // RFConfiguration item 1, (on, autoRfca=false) → bool
    reader.power_down();                                           // PowerDown, (wakeupSources=0x20) → bool
    periph_printf("powered down; the next I2C access wakes the chip\n");
}

void loop() {
}
