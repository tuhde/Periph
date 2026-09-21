#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/spi_master.h"
#include "driver/gpio.h"
#include "SPIConnectionESPIDF.h"
#include "OutputPinESPIDF.h"
#include "AD7705.h"

static const int MOSI_PIN = 23;
static const int MISO_PIN = 19;
static const int SCLK_PIN = 18;
static const int CS_PIN   = 5;
static const int RESET_PIN = 17;

extern "C" void app_main(void) {
    spi_bus_config_t bus_cfg = {};
    bus_cfg.mosi_io_num   = MOSI_PIN;
    bus_cfg.miso_io_num   = MISO_PIN;
    bus_cfg.sclk_io_num   = SCLK_PIN;
    bus_cfg.quadwp_io_num = -1;
    bus_cfg.quadhd_io_num = -1;
    spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    spi_device_interface_config_t dev_cfg = {};
    dev_cfg.mode            = 3;
    dev_cfg.clock_speed_hz  = 5000000;
    dev_cfg.spics_io_num    = CS_PIN;
    dev_cfg.queue_size      = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    gpio_reset_pin((gpio_num_t)RESET_PIN);
    gpio_set_direction((gpio_num_t)RESET_PIN, GPIO_MODE_OUTPUT);
    gpio_set_level((gpio_num_t)RESET_PIN, 1);

    SPIConnectionESPIDF connection(dev);                                          // Create SPI connection, (dev) → SPIConnectionESPIDF
    OutputPinESPIDF reset_pin((gpio_num_t)RESET_PIN);                              // Construct hardware reset OutputPin, (gpio_num_t=17) → OutputPinESPIDF
    AD7705Full adc(connection, 2.5f, AD7705Minimal::MCLK_2_4576MHZ, &reset_pin);    // Construct and initialise the AD7705, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz, reset_pin=&reset_pin) → AD7705Full

    adc.configure(2, AD7705Full::GAIN_8, true, true, 60);                         // Configure channel 2, (channel=2, gain=GAIN_8, bipolar=true, buffered=true, output_rate_hz=60) → None
    adc.self_calibrate(2);                                                          // Self-calibrate channel 2, (channel=2) → None

    uint32_t off2 = adc.get_offset_calibration(2);                                  // Read offset calibration, (channel=2) → uint32_t 24-bit
    uint32_t gain2 = adc.get_gain_calibration(2);                                   // Read gain calibration, (channel=2) → uint32_t 24-bit
    printf("ch2 offset=%lu gain=%lu\n", (unsigned long)off2, (unsigned long)gain2);

    uint16_t raw1 = adc.read_raw(1);                                                // Read raw 16-bit code, (channel=1) → uint16_t
    float v1 = adc.read_voltage(1);                                                 // Read voltage, (channel=1) → float V
    float v2 = adc.read_voltage(2);                                                 // Read voltage, (channel=2) → float V
    printf("ch1 raw=%u ch1 v=%f ch2 v=%f\n", raw1, (double)v1, (double)v2);

    adc.standby();                                                                  // Enter standby, () → None
    vTaskDelay(pdMS_TO_TICKS(100));
    adc.wakeup();                                                                   // Exit standby, () → None

    adc.reset();                                                                    // Hardware reset, () → None
}
