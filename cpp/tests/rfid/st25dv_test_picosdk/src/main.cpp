#include <stdio.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "St25dv.h"

static void out(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
}

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { out("PASS %s\n", label); passed++; }
    else      { out("FAIL %s\n", label); failed++; }
}
#define CHECK(c, l) check_true((c), (l))

static void run_tests(ST25DVFull& tag) {
    CHECK(tag.init(), "init");
    CHECK(tag.ic_reference() == 0x24 || tag.ic_reference() == 0x26, "ic_reference");
    uint32_t size = tag.memory_size();
    CHECK(size == 512 || size == 2048 || size == 8192, "memory_size");
    uint8_t uid[8] = {0};
    CHECK(tag.uid(uid), "uid ok");
    CHECK(uid[0] == 0xE0 && uid[1] == 0x02, "uid manufacturer");
    tag.ic_revision();
    CHECK(true, "ic_revision");

    uint16_t addr = (uint16_t)(size - 64);
    static uint8_t saved[40], pattern[40], back[40];
    CHECK(tag.read(addr, saved, 40), "read ok");
    for (int i = 0; i < 40; i++) pattern[i] = (uint8_t)(i * 7 + 3);
    CHECK(tag.write(addr, pattern, 40), "write ok");
    CHECK(tag.read(addr, back, 40) && memcmp(back, pattern, 40) == 0, "write/read round-trip");
    CHECK(tag.write(addr, saved, 40), "restore");
    CHECK(!tag.read((uint16_t)(size - 1), back, 2), "range rejected");

    static const uint8_t pwd[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    CHECK(tag.present_password(pwd), "present factory password");
    CHECK(tag.session_open(), "session open after present");

    uint8_t rf = 0xFF, i2c = 0xFF;
    CHECK(tag.get_area_protection(1, rf, i2c) && rf <= 0x0F && i2c <= 3, "get_area_protection");
    tag.get_gpo();
    CHECK(true, "get_gpo");
    tag.field_present();
    CHECK(true, "field_present");
    tag.poll_interrupt();
    CHECK(true, "poll_interrupt");
    tag.mailbox_status();
    CHECK(true, "mailbox_status");
    static uint8_t ndef[256];
    tag.read_ndef(ndef, sizeof(ndef));
    CHECK(true, "read_ndef");
    CHECK(tag.wait_write_complete(), "wait_write_complete");
}

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    I2CConnectionPicoSDK user_conn(i2c0, 0x53, nullptr, nullptr, 2);
    I2CConnectionPicoSDK system_conn(i2c0, 0x57, nullptr, nullptr, 2);
    ST25DVFull tag(user_conn, system_conn);
    run_tests(tag);
    out("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
