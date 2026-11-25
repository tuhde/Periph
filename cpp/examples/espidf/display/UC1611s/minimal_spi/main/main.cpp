// UC1611s minimal example (4-wire SPI): EA DOGXL240-7, ESP-IDF (ESP32-S3 / ESP32, SPI2)
// Wiring: SI=GPIO23, SCK=GPIO18, CS0=GPIO5, CD=GPIO17, RST=GPIO16; VDD=3V3 (no BUSY line).
// Module: EA DOGXL240-7 (240 x 128, 3.3 V only), 4-wire SPI: BM0 = D13 = VSS. Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD.
// Minimal use: a border and a diagonal on the 240 x 128 panel.
#include <stdio.h>
#include <driver/spi_master.h>
#include <driver/gpio.h>
#include <esp_timer.h>
#include "DisplayConnectionESPIDF.h"
#include "OutputPinESPIDF.h"
#include "InputPinESPIDF.h"
#include "UC1611s.h"

#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#define DELAY_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms))
#define PRINT printf
#define NOW_MS() ((uint32_t)(esp_timer_get_time() / 1000))

static void run(UC1611sSpiMinimal<DisplayConnectionESPIDF>& lcd) {
    lcd.clear();                                    // Clear framebuffer, ()
    for (int16_t x = 0; x < 240; x++) {
        lcd.set_pixel(x, 0);                        // Set pixel, (x 0..239, y 0..127, on=true)
        lcd.set_pixel(x, 127);                      // Set pixel, (x 0..239, y 0..127, on=true)
        lcd.set_pixel(x, static_cast<int16_t>(x * 127 / 239));  // Set pixel, (x 0..239, y 0..127, on=true)
    }
    for (int16_t y = 0; y < 128; y++) {
        lcd.set_pixel(0, y);                        // Set pixel, (x 0..239, y 0..127, on=true)
        lcd.set_pixel(239, y);                      // Set pixel, (x 0..239, y 0..127, on=true)
    }
    lcd.show();                                     // Send framebuffer to the display, ()
    PRINT("done\n");
}

extern "C" void app_main(void) {
    spi_bus_config_t bus_cfg = {};
    bus_cfg.mosi_io_num = static_cast<gpio_num_t>(23);
    bus_cfg.miso_io_num = static_cast<gpio_num_t>(-1);
    bus_cfg.sclk_io_num = static_cast<gpio_num_t>(18);
    bus_cfg.quadwp_io_num = static_cast<gpio_num_t>(-1);
    bus_cfg.quadhd_io_num = static_cast<gpio_num_t>(-1);
    bus_cfg.max_transfer_sz = 4092;
    spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    // CS# is driven by the SPI driver (spics_io_num), so the connection gets cs = nullptr.
    spi_device_interface_config_t dev_cfg = {};
    dev_cfg.clock_speed_hz = 1000000;
    dev_cfg.mode = 0;
    dev_cfg.spics_io_num = static_cast<gpio_num_t>(5);
    dev_cfg.queue_size = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    OutputPinESPIDF dc(static_cast<gpio_num_t>(17));    // Create D/C# pin, (gpio)
    OutputPinESPIDF res(static_cast<gpio_num_t>(16));   // Create RES# pin, (gpio)

    DisplayConnectionESPIDF connection(dev, nullptr, &dc, &res, nullptr);   // Create display connection, (dev, cs=nullptr, dc, res, busy, wireMode=kSimplex)
    UC1611sSpiMinimal<DisplayConnectionESPIDF> lcd(connection);  // Create UC1611s driver, (connection, width=240, height=128)
    if (!lcd.valid()) {
        PRINT("UC1611s init failed (check wiring)\n");
        return;
    }
    run(lcd);
}
