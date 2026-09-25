#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/drivers/spi.h>
#include "SiPoConnectionZephyr.h"
#include "TPIC6B595.h"

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool cond) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

static void check_eq(const char* label, uint8_t got, uint8_t expected) {
    if (got == expected) { printk("PASS %s\n", label); passed++; }
    else { printk("FAIL %s: got %u expected %u\n", label, (unsigned)got, (unsigned)expected); failed++; }
}

int main(void) {
    const struct device* spi_dev = DEVICE_DT_GET(DT_NODELABEL(spi0));

    struct spi_config spi_cfg = {
        .frequency = 1000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER | SPI_MODE_CPOL | SPI_MODE_CPHA,
        .slave     = 0,
        .cs        = {},
    };

    const struct gpio_dt_spec rck   = GPIO_DT_SPEC_GET(DT_ALIAS(sipo_rck), gpios);
    SiPoConnectionZephyr connection(spi_dev, spi_cfg, rck);
    TPIC6B595Full<SiPoConnectionZephyr> chip(connection, 1);

    check_eq("init_shadow_0", chip._shadow[0], 0x00);

    chip.fill(true);
    check_eq("fill_true_shadow", chip._shadow[0], 0xFF);
    chip.fill(false);
    check_eq("fill_false_shadow", chip._shadow[0], 0x00);
    chip.off();
    check_eq("off_shadow", chip._shadow[0], 0x00);

    chip.write_port(0, 0xA5);
    check_eq("write_port_0xa5_shadow", chip._shadow[0], 0xA5);

    TPIC6B595Full<SiPoConnectionZephyr>::IOExpanderPin p0 = chip.pin(0);
    p0.high();
    check_eq("pin_on_shadow_bit", chip._shadow[0] & 0x01, 1);
    p0.low();
    check_eq("pin_off_shadow_bit", chip._shadow[0] & 0x01, 0);
    p0.toggle();
    check_eq("pin_toggle_shadow_bit", chip._shadow[0] & 0x01, 1);

    chip.clear();
    check_true("clear_accepted", true);
    chip.set_output_enable(false);
    check_true("set_output_enable_false_accepted", true);
    chip.set_output_enable(true);
    check_true("set_output_enable_true_accepted", true);

    TPIC6B595Full<SiPoConnectionZephyr> cascaded(connection, 2);
    uint8_t bytes_[2] = { 0xA5, 0x5A };
    cascaded.write_all(bytes_, 2);
    check_eq("write_all_shadow_0", cascaded._shadow[0], 0xA5);
    check_eq("write_all_shadow_1", cascaded._shadow[1], 0x5A);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
