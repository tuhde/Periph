// TMC2130 Minimal example: move a stepper with STEP/DIR pulses.
// Wiring: SPI Mode 3 (SPI_MODE pin high, SCK idles high), 1 MHz; STEP/DIR/ENN (and DIAG0) on plain GPIO.
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/spi_master.h"
#include "driver/gpio.h"
#include "SPIConnectionESPIDF.h"
#include "OutputPinESPIDF.h"
#include "TMC2130.h"

#define LOG(...) printf(__VA_ARGS__)
#define SLEEP_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms))

static void example(Connection& connection) {
    OutputPinESPIDF stp((gpio_num_t)21);
    OutputPinESPIDF dir((gpio_num_t)22);
TMC2130Minimal motor(connection, &stp, &dir);         // Create TMC2130 driver, (connection, step_pin, dir_pin, r_sense_ohm=0.11 Ω, en_pin=nullptr)
    if (!motor.ok()) { LOG("TMC2130 not found\n"); return; }

    motor.setCurrent(0.8f);                           // Set run current, (rms_a A RMS) → bool
    motor.enable();                                   // Power stage on, () → void
    for (int i = 0; i < 2; i++) {
        motor.move(i == 0 ? 400 : -400, 200.0f);      // Move, (full_steps, full_steps_per_s full steps/s) → bool
        SLEEP_MS(500);
        LOG("standstill=%d fault=%d\n", (int)motor.isStandstill(), (int)motor.readFault());  // isStandstill() → bool, readFault() → uint8_t bitmask
    }
    motor.disable();                                  // Power stage off, () → void
}

extern "C" void app_main(void) {
    spi_bus_config_t bus_cfg = {};
    bus_cfg.mosi_io_num   = 23;
    bus_cfg.miso_io_num   = 19;
    bus_cfg.sclk_io_num   = 18;
    bus_cfg.quadwp_io_num = -1;
    bus_cfg.quadhd_io_num = -1;
    spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    spi_device_interface_config_t dev_cfg = {};
    dev_cfg.mode            = 3;
    dev_cfg.clock_speed_hz  = 1000000;
    dev_cfg.spics_io_num    = 5;
    dev_cfg.queue_size      = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    SPIConnectionESPIDF connection(dev);  // Create SPI connection, (dev) → SPIConnectionESPIDF
    example(connection);
    while (1) vTaskDelay(pdMS_TO_TICKS(1000));
}
