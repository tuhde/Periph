#include <stdio.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <driver/spi_master.h>
#include <driver/gpio.h>
#include "SiPoConnectionESPIDF.h"
#include "TPIC6B595.h"

extern "C" void app_main(void) {
    spi_bus_config_t bus_cfg = {};
    bus_cfg.mosi_io_num = static_cast<gpio_num_t>(23);
    bus_cfg.miso_io_num = static_cast<gpio_num_t>(-1);
    bus_cfg.sclk_io_num = static_cast<gpio_num_t>(18);
    bus_cfg.quadwp_io_num = static_cast<gpio_num_t>(-1);
    bus_cfg.quadhd_io_num = static_cast<gpio_num_t>(-1);
    bus_cfg.max_transfer_sz = 32;
    spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    spi_device_interface_config_t dev_cfg = {};
    dev_cfg.clock_speed_hz = 1000000;
    dev_cfg.mode = 0;
    dev_cfg.spics_io_num = static_cast<gpio_num_t>(-1);
    dev_cfg.queue_size = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    gpio_num_t rck   = static_cast<gpio_num_t>(17);
    gpio_num_t srclr = static_cast<gpio_num_t>(16);
    gpio_num_t g     = static_cast<gpio_num_t>(15);
    SiPoConnectionESPIDF connection(dev, rck, srclr, g);                     // Create SiPo connection, (spi, rck, srclr, g)
    TPIC6B595Full<SiPoConnectionESPIDF> chip(connection, 2);                 // Create TPIC6B595 full driver, (connection, num_devices=2)
                                                                              // two cascaded devices — 16 outputs total (DRAIN0..DRAIN15)

    auto p0 = chip.pin(0);                                                    // Get pin proxy for DRAIN0 of device 0, (n=0) → IOExpanderPin

    // --- Pin-level control ---
    p0.high();                                                                // Set DRAIN0 ON, () → void
                                                                              // sets shadow[0] bit 0, reverses the cascade, shifts out and pulses RCK
    p0.low();                                                                 // Set DRAIN0 OFF, () → void
                                                                              // clears shadow[0] bit 0, retransmits and latches
    p0.toggle();                                                              // Invert shadow bit, () → void

    uint8_t state = p0.read();                                                // Read pin state, () → uint8_t
                                                                              // returns the shadow bit (no bus read — SiPo is write-only)
    p0.write(HIGH);                                                           // Write pin high, (v=HIGH) → void
                                                                              // equivalent to high(); updates shadow, retransmits, latches
    p0.set(true);                                                             // OutputPin set, (high=true) → void
                                                                              // same path as high()/write(HIGH), but matches the OutputPin contract

    // --- Port-level bulk write ---
    chip.write_port(0, 0xAA);                                                 // Write device 0 outputs, (port=0, mask=0xAA) → void
                                                                              // sets DRAIN{1,3,5,7} ON, DRAIN{0,2,4,6} OFF; preserves device 1
    chip.write_port(1, 0x55);                                                 // Write device 1 outputs, (port=1, mask=0x55) → void

    // --- Bulk fill / off ---
    chip.fill(true);                                                          // Set every output ON, (value=true) → void
                                                                              // fills every shadow byte with 0xFF and retransmits — fast "all on" path
    chip.fill(false);                                                         // Set every output OFF, (value=false) → void
                                                                              // fills every shadow byte with 0x00 and retransmits — fast "all off" path
    chip.off();                                                               // Turn every output off, () → void
                                                                              // shorthand for fill(false); the safe initial state

    // --- Multi-device bulk write ---
    uint8_t bytes_[2] = { 0x01, 0x80 };
    chip.write_all(bytes_, 2);                                                // Write all device bytes, (values=uint8_t*, len=2) → void
                                                                              // updates both shadow bytes and performs one transmit + latch

    // --- Hardware features (Full only) ---
    chip.clear();                                                             // Pulse SRCLR, () → int
                                                                              // clears the shift register only; outputs unaffected until next RCK pulse
    chip.set_output_enable(false);                                            // Force every output off via G, (enabled=false) → int
                                                                              // drives G HIGH, blanking outputs without disturbing the shadow register
    vTaskDelay(pdMS_TO_TICKS(100));
    chip.set_output_enable(true);                                             // Re-enable outputs, (enabled=true) → int
                                                                              // drives G LOW; outputs resume from the previously-latched state
}
