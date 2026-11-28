#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/sys/printk.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>
#include "I2CConnectionZephyr.h"
#include "St25dv.h"

#define I2C_NODE DT_NODELABEL(i2c0)

static void out(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vprintk(fmt, ap);
    va_end(ap);
}
static void pause_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }

static void print_hex(const char* label, const uint8_t* d, size_t n) {
    out("%s", label);
    for (size_t i = 0; i < n; i++) out("%02X", d[i]);
    out("\n");
}

static uint8_t buf[256];

static void on_event(uint8_t status) {
    out("  GPO event 0x%02X%s%s%s\n", status,
        (status & ST25DVSource::FIELD_RISING) ? " field-on" : "",
        (status & ST25DVSource::FIELD_FALLING) ? " field-off" : "",
        (status & ST25DVSource::RF_WRITE) ? " rf-write" : "");
}

static void run(ST25DVFull& tag) {
    // --- Identify the tag ---
    // IC_REF tells the 04K apart from the 16K/64K; the size comes from MEM_SIZE.
    tag.init();                                                         // Read MEM_SIZE, BLK_SIZE, IC_REF, () → bool
    out("IC_REF 0x%02X revision %u\n", tag.ic_reference(), tag.ic_revision()); // Get IC reference, () → uint8_t ; Read IC revision, () → uint8_t
    uint8_t uid[8];
    tag.uid(uid);                                                       // Read unique ID, (out[8]) → bool
    print_hex("UID ", uid, 8);
    out("%lu bytes\n", (unsigned long)tag.memory_size());               // Get user memory size, () → bytes

    // --- NDEF text record a phone can read ---
    // Record header D1 (MB/ME/SR, well-known), type 'T', status 02 (language "en").
    static const char text[] = "Hello from Periph";
    uint8_t record[64];
    size_t tl = sizeof(text) - 1;
    record[0] = 0xD1; record[1] = 0x01; record[2] = (uint8_t)(3 + tl); record[3] = 0x54;
    record[4] = 0x02; record[5] = 'e'; record[6] = 'n';
    memcpy(record + 7, text, tl);
    tag.write_ndef(record, 7 + tl);                                     // Write NDEF message, (message, length) → bool
    size_t n = tag.read_ndef(buf, sizeof(buf));                         // Read NDEF message, (buf, capacity) → size_t bytes
    out("NDEF read back %s\n", (n == 7 + tl && memcmp(buf, record, n) == 0) ? "ok" : "MISMATCH");

    // --- Raw bytes across a 256-byte page boundary ---
    // The driver splits the write into 16-byte-aligned pieces and ACK-polls each.
    uint8_t saved[32], pattern[32];
    tag.read(0x00F8, saved, 32);                                        // Read user memory, (address, buf, length) → bool
    for (int i = 0; i < 32; i++) pattern[i] = (uint8_t)(0x40 + i);
    tag.write(0x00F8, pattern, 32);                                     // Write user memory, (address, data, length) → bool
    tag.wait_write_complete();                                          // Wait for the write cycle, () → bool
    tag.read(0x00F8, buf, 32);                                          // Read user memory, (address, buf, length) → bool
    out("page-crossing write %s\n", memcmp(buf, pattern, 32) == 0 ? "ok" : "MISMATCH");
    tag.write(0x00F8, saved, 32);                                       // Write user memory, (address, data, length) → bool

    // --- Password session and two memory areas ---
    // Area 1 = first 1 KiB (stays open), area 2 = the rest: RF writes need RF
    // password 1, I2C writes need the I2C session. The factory I2C password is 0.
    static const uint8_t pwd[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    out("session before %u\n", tag.session_open());                     // Query I²C security session, () → bool
    out("password accepted %u\n", tag.present_password(pwd));           // Present I²C password, (password[8]) → bool
    uint32_t first = tag.memory_size() < 1024 ? tag.memory_size() : 1024;
    uint8_t end1 = (uint8_t)(first / 32 - 1);
    uint8_t last = (uint8_t)(tag.memory_size() / 32 - 1);
    tag.set_area_end(1, end1);                                          // Set end of area 1-3, (area 1–3, end_block 0–255) → bool
    tag.set_area_end(2, last);                                          // Set end of area 1-3, (area 1–3, end_block 0–255) → bool
    tag.set_area_end(3, last);                                          // Set end of area 1-3, (area 1–3, end_block 0–255) → bool
    tag.set_area_protection(2, 0x05, 0x01);                             // Set area protection, (area 1–4, rf 0–15, i2c 0–3) → bool
    uint8_t rf = 0, i2c = 0;
    tag.get_area_protection(2, rf, i2c);                                // Get area protection, (area 1–4, rf&, i2c&) → bool
    out("area 2 rf 0x%02X i2c %u\n", rf, i2c);

    // --- Change and restore the I2C password ---
    static const uint8_t new_pwd[8] = {0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0};
    tag.write_password(new_pwd);                                        // Change I²C password, (password[8]) → bool
    out("new password accepted %u\n", tag.present_password(new_pwd));   // Present I²C password, (password[8]) → bool
    tag.write_password(pwd);                                            // Change I²C password, (password[8]) → bool
    out("factory password restored %u\n", tag.present_password(pwd));   // Present I²C password, (password[8]) → bool

    // --- GPO events while a phone approaches ---
    // Field change and RF write events pulse GPO. With a GPO pin on the
    // connection on_interrupt() delivers them; here IT_STS_Dyn is also polled.
    // Hold a phone to the antenna now.
    tag.set_interrupt_pulse(3);                                         // Set GPO pulse duration, (value 0–7) → bool
    tag.set_gpo(ST25DVFull::GPO_FIELD_CHANGE);                          // Write static GPO register, (sources, enable=true) → bool
    tag.enable_interrupt(ST25DVSource::FIELD_RISING | ST25DVSource::RF_WRITE); // Enable interrupt sources, (source) → bool
    out("GPO register 0x%02X\n", tag.get_gpo());                        // Read static GPO register, () → uint8_t
    tag.on_interrupt(on_event);                                         // Subscribe to GPO events, (callback, intPin=nullptr) → void
    for (int i = 0; i < 10; i++) {
        uint8_t s = tag.poll_interrupt();                               // Read and clear IT_STS_Dyn, () → uint8_t
        if (s) on_event(s);
        out("  field present %u\n", tag.field_present());               // Query RF field, () → bool
        pause_ms(1000);
    }
    tag.off_interrupt();                                                // Unsubscribe, () → void
    tag.disable_interrupt(ST25DVSource::RF_WRITE);                      // Disable interrupt sources, (source) → bool

    // --- Fast transfer mode: host <-> phone mailbox ---
    // The host puts a message; an RF reader (e.g. the ST25 app) reads it and can
    // answer. The watchdog releases an unread message after 2^(7-1) x 30 ms.
    tag.enable_mailbox();                                               // Enable fast transfer mode, () → bool
    tag.set_mailbox_watchdog(7);                                        // Set mailbox watchdog, (value 0–7) → bool
    static const uint8_t hello[] = {'h', 'e', 'l', 'l', 'o', ' ', 'r', 'e', 'a', 'd', 'e', 'r'};
    tag.mailbox_write(hello, sizeof(hello));                            // Put mailbox message, (data, length 1–256) → bool
    for (int i = 0; i < 10; i++) {
        ST25DVMailboxStatus st = tag.mailbox_status();                  // Decode mailbox status, () → ST25DVMailboxStatus
        if (st.rf_put) {
            n = tag.mailbox_read(buf, sizeof(buf));                     // Read mailbox message, (buf, capacity) → size_t bytes
            print_hex("reader says ", buf, n);
            break;
        }
        out("  mailbox host_put %u rf_put %u host_miss %u\n", st.host_put, st.rf_put, st.host_miss);
        pause_ms(1000);
    }
    tag.disable_mailbox();                                              // Disable fast transfer mode, () → bool

    // --- Energy harvesting and RF state ---
    tag.set_energy_harvesting(true);                                    // Control energy harvesting, (enabled, forced=false) → bool
    pause_ms(1000);
    tag.set_energy_harvesting(false);                                   // Control energy harvesting, (enabled, forced=false) → bool
    tag.set_rf_state(false, true);                                      // Set RF interface state, (disable=false, sleep=false) → bool
    out("RF asleep: a phone no longer sees the tag\n");
    pause_ms(2000);
    tag.set_rf_state();                                                 // Set RF interface state, (disable=false, sleep=false) → bool

    // --- Restore the delivery defaults ---
    // One area, no protection, field-change GPO only, mailbox off, CC unlocked.
    tag.set_area_protection(2, 0x00, 0x00);                             // Set area protection, (area 1–4, rf 0–15, i2c 0–3) → bool
    tag.set_area_end(1, last);                                          // Set end of area 1-3, (area 1–3, end_block 0–255) → bool
    tag.set_gpo(ST25DVFull::GPO_FIELD_CHANGE);                          // Write static GPO register, (sources, enable=true) → bool
    tag.set_gpo_runtime(true);                                          // Switch GPO output at runtime, (enable) → bool
    tag.write_system(0x000D, (uint8_t)0x00);                            // Write system area, (address, value) → bool
    uint8_t mb_mode = 0xFF;
    tag.read_system(0x000D, &mb_mode, 1);                               // Read system area, (address, buf, length) → bool
    out("MB_MODE %u\n", mb_mode);
    tag.lock_ccfile(false);                                             // Lock CC file blocks, (lock=true) → bool
    out("done\n");
}

int main(void) {
    const struct device* i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr user_conn(i2c_dev, 0x53, nullptr, nullptr, 2);             // Create user-area connection, (dev, addr=0x53, int=null, en=null, reg_bytes=2)
    I2CConnectionZephyr system_conn(i2c_dev, 0x57, nullptr, nullptr, 2);           // Create system-area connection, (dev, addr=0x57, int=null, en=null, reg_bytes=2)
    ST25DVFull tag(user_conn, system_conn);                                       // Create ST25DV driver, (user, system, gpo_active_high=false)
    run(tag);
    return 0;
}
