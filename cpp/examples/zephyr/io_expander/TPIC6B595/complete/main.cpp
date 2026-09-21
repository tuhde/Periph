#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/drivers/spi.h>
#include "SiPoConnectionZephyr.h"
#include "TPIC6B595.h"

int main() {
    const struct device* spi_dev = DEVICE_DT_GET(DT_NODELABEL(spi0));

    struct spi_config spi_cfg = {
        .frequency = 1000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER | SPI_MODE_CPOL | SPI_MODE_CPHA,
        .slave     = 0,
        .cs        = NULL,
    };

    const struct gpio_dt_spec rck   = GPIO_DT_SPEC_GET(DT_NODELABEL(gpio0), gpios);
    SiPoConnectionZephyr connection(spi_dev, spi_cfg, rck);        // Create SiPo connection, (dev, config, rck, srclr={}, g={})
    TPIC6B595Full<SiPoConnectionZephyr> chip(connection, 2);       // Create TPIC6B595 full driver, (connection, num_devices=2)
                                                                    // two cascaded devices — 16 outputs total (DRAIN0..DRAIN15)

    auto p0 = chip.pin(0);                                         // Get pin proxy for DRAIN0 of device 0, (n=0) → IOExpanderPin

    // --- Pin-level control ---
    p0.high();                                                     // Set DRAIN0 ON, () → void
                                                                    // sets shadow[0] bit 0, reverses the cascade, shifts out and pulses RCK
    p0.low();                                                      // Set DRAIN0 OFF, () → void
                                                                    // clears shadow[0] bit 0, retransmits and latches
    p0.toggle();                                                   // Invert shadow bit, () → void

    uint8_t state = p0.read();                                     // Read pin state, () → uint8_t
                                                                    // returns the shadow bit (no bus read — SiPo is write-only)
    p0.write(HIGH);                                                // Write pin high, (v=HIGH) → void
                                                                    // equivalent to high(); updates shadow, retransmits, latches
    p0.set(true);                                                  // OutputPin set, (high=true) → void
                                                                    // same path as high()/write(HIGH), but matches the OutputPin contract

    // --- Port-level bulk write ---
    chip.write_port(0, 0xAA);                                      // Write device 0 outputs, (port=0, mask=0xAA) → void
                                                                    // sets DRAIN{1,3,5,7} ON, DRAIN{0,2,4,6} OFF; preserves device 1
    chip.write_port(1, 0x55);                                      // Write device 1 outputs, (port=1, mask=0x55) → void

    // --- Bulk fill / off ---
    chip.fill(true);                                               // Set every output ON, (value=true) → void
                                                                    // fills every shadow byte with 0xFF and retransmits — fast "all on" path
    chip.fill(false);                                              // Set every output OFF, (value=false) → void
                                                                    // fills every shadow byte with 0x00 and retransmits — fast "all off" path
    chip.off();                                                    // Turn every output off, () → void
                                                                    // shorthand for fill(false); the safe initial state

    // --- Multi-device bulk write ---
    uint8_t bytes_[2] = { 0x01, 0x80 };
    chip.write_all(bytes_, 2);                                     // Write all device bytes, (values=uint8_t*, len=2) → void
                                                                    // updates both shadow bytes and performs one transmit + latch

    // --- Hardware features (Full only) ---
    chip.clear();                                                  // Pulse SRCLR, () → int
                                                                    // clears the shift register only; outputs unaffected until next RCK pulse
    chip.set_output_enable(false);                                 // Force every output off via G, (enabled=false) → int
                                                                    // drives G HIGH, blanking outputs without disturbing the shadow register
    k_msleep(100);
    chip.set_output_enable(true);                                  // Re-enable outputs, (enabled=true) → int
                                                                    // drives G LOW; outputs resume from the previously-latched state

    return 0;
}
