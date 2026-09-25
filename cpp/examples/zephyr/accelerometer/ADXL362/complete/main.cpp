#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "SPIConnectionZephyr.h"
#include "ADXL362.h"

#ifndef ADXL362_SPI_NODE
#define ADXL362_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef ADXL362_CS_GPIOS
#define ADXL362_CS_GPIOS GPIO_DT_SPEC_GET_BY_IDX(ADXL362_SPI_NODE, cs_gpios, 0)
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(ADXL362_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 8000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .slave     = 0,
        .cs        = { .gpio = ADXL362_CS_GPIOS, .delay = 0 },
    };
    SPIConnectionZephyr connection(dev, cfg);                              // Create SPI connection, (dev, cfg) → SPIConnectionZephyr
    ADXL362Full accel(connection);                                           // Create ADXL362 Full driver, (connection) → ADXL362Full

    uint8_t devad, devmst, partid, revid;
    accel.device_id(devad, devmst, partid, revid);                           // Read device IDs, (devid_ad, devid_mst, partid, revid) → 4× byte
    printk("DEVID_AD=0x%02X DEVID_MST=0x%02X PARTID=0x%02X REVID=0x%02X\n",
           devad, devmst, partid, revid);

    accel.set_range(4);                                                      // Set measurement range, (range_g=4) → None
    accel.set_odr(200.0f);                                                   // Set output data rate, (odr_hz=200.0) → None
    accel.set_half_bandwidth(true);                                          // Set antialiasing bandwidth, (enabled=true) → None
    accel.set_noise_mode(ADXL362Full::NOISE_LOW);                            // Set noise mode, (mode=NOISE_LOW=1) → None

    float x, y, z;
    accel.read(x, y, z);                                                     // Read 12-bit acceleration, (x, y, z) → g, g, g
    printk("12-bit: x=%+.3f  y=%+.3f  z=%+.3f\n", x, y, z);

    accel.read_8bit(x, y, z);                                                // Read 8-bit acceleration, (x, y, z) → g, g, g
    printk(" 8-bit: x=%+.3f  y=%+.3f  z=%+.3f\n", x, y, z);

    float t = accel.temperature();                                           // Read temperature, () → float °C
    printk("temperature: %.2f C\n", t);

    uint8_t raw_status = accel.status();                                     // Read STATUS register, () → byte
    printk("status: 0x%02X\n", raw_status);
    printk("awake: %d\n", accel.awake() ? 1 : 0);                            // Check AWAKE bit, () → bool
    printk("data_ready: %d\n", accel.data_ready() ? 1 : 0);                  // Check DATA_READY, () → bool
    printk("fifo_entries: %u\n", accel.fifo_entries());                      // Read FIFO entry count, () → uint16_t

    accel.configure_fifo(ADXL362Full::FIFO_STREAM, false, 128);              // Configure FIFO, (mode=STREAM=2, store_temp=false, watermark=128) → None
    accel.set_activity_threshold(0.5f, true);                                // Set activity threshold, (threshold_g=0.5, referenced=true) → None
    accel.set_activity_time(5);                                              // Set activity time, (samples=5) → None
    accel.set_inactivity_threshold(0.2f, true);                              // Set inactivity threshold, (threshold_g=0.2, referenced=true) → None
    accel.set_inactivity_time(30);                                           // Set inactivity time, (samples=30) → None
    accel.enable_activity_detection(true);                                   // Enable activity detection, (enabled=true) → None
    accel.enable_inactivity_detection(true);                                 // Enable inactivity detection, (enabled=true) → None
    accel.set_link_loop_mode(ADXL362Full::LINKLOOP_LOOP);                    // Set link/loop mode, (mode=LOOP=3) → None

    accel.set_interrupt(1, ADXL362Full::SOURCE_DATA_READY, true);            // Map DATA_READY to INT1, (pin=1, source=DATA_READY=0, enabled=true) → None
    accel.set_interrupt(2, ADXL362Full::SOURCE_AWAKE, true);                 // Map AWAKE to INT2, (pin=2, source=AWAKE=6, enabled=true) → None
    accel.set_interrupt_polarity(1, true);                                   // Set INT1 active-low, (pin=1, active_low=true) → None

    accel.self_test(true);                                                   // Enable self-test, (enabled=true) → None
    k_msleep(500);
    accel.self_test(false);                                                  // Disable self-test, (enabled=false) → None

    accel.soft_reset();                                                      // Soft-reset the chip, () → None
    return 0;
}