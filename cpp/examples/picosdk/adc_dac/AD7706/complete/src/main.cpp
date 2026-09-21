#include <stdio.h>
#include "pico/stdlib.h"
#include <hardware/spi.h>
#include <hardware/gpio.h>
#include "SPIConnectionPicoSDK.h"
#include "OutputPinPicoSDK.h"
#include "AD7706.h"

static const uint MOSI_PIN = 19;
static const uint MISO_PIN = 16;
static const uint SCLK_PIN = 18;
static const uint CS_PIN   = 5;
static const uint RESET_PIN = 14;

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 5000000);
    spi_set_format(spi0, 8, SPI_CPOL_1, SPI_CPHA_1, SPI_MSB_FIRST);
    gpio_set_function(MOSI_PIN, GPIO_FUNC_SPI);
    gpio_set_function(MISO_PIN, GPIO_FUNC_SPI);
    gpio_set_function(SCLK_PIN, GPIO_FUNC_SPI);

    SPIConnectionPicoSDK connection(spi0, CS_PIN);                                  // Create SPI connection, (spi0, cs_pin=5) → SPIConnectionPicoSDK
    OutputPinPicoSDK reset_pin(RESET_PIN);                                          // Construct hardware reset OutputPin, (pin=14) → OutputPinPicoSDK
    AD7706Full adc(connection, 2.5f, AD7706Minimal::MCLK_2_4576MHZ, &reset_pin);     // Construct and initialise the AD7706, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz, reset_pin=&reset_pin) → AD7706Full

    adc.configure(2, AD7706Full::GAIN_8, true, true, 60);                            // Configure channel 2, (channel=2, gain=GAIN_8, bipolar=true, buffered=true, output_rate_hz=60) → None
    adc.self_calibrate(2);                                                           // Self-calibrate channel 2, (channel=2) → None
    adc.configure(3, AD7706Full::GAIN_8, true, true, 60);                            // Configure channel 3, (channel=3, gain=GAIN_8, bipolar=true, buffered=true, output_rate_hz=60) → None
    adc.self_calibrate(3);                                                           // Self-calibrate channel 3, (channel=3) → None

    uint32_t off2 = adc.get_offset_calibration(2);                                   // Read offset calibration, (channel=2) → uint32_t 24-bit
    uint32_t gain2 = adc.get_gain_calibration(2);                                    // Read gain calibration, (channel=2) → uint32_t 24-bit
    printf("ch2 offset=%lu gain=%lu\n", (unsigned long)off2, (unsigned long)gain2);

    uint16_t raw1 = adc.read_raw(1);                                                 // Read raw 16-bit code, (channel=1) → uint16_t
    float v1 = adc.read_voltage(1);                                                  // Read voltage, (channel=1) → float V
    float v2 = adc.read_voltage(2);                                                  // Read voltage, (channel=2) → float V
    float v3 = adc.read_voltage(3);                                                  // Read voltage, (channel=3) → float V
    printf("ch1 raw=%u ch1 v=%f ch2 v=%f ch3 v=%f\n", raw1, (double)v1, (double)v2, (double)v3);

    adc.standby();                                                                   // Enter standby, () → None
    sleep_ms(100);
    adc.wakeup();                                                                    // Exit standby, () → None

    adc.reset();                                                                     // Hardware reset, () → None
    return 0;
}
