#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Isl29125.h"

static UART_HandleTypeDef huart2;
static I2C_HandleTypeDef hi2c1;

// SysTick_Handler overrides the weak default in startup_stm32f411xe.s —
// without this, HAL_GetTick()/HAL_Delay() never advance.
extern "C" void SysTick_Handler(void) {
    HAL_IncTick();
}

// Retargets printf() to USART2 (PA2/PA3), which NUCLEO-F411RE's on-board
// ST-LINK exposes as a USB virtual COM port at 115200 baud.
extern "C" int _write(int file, char* ptr, int len) {
    (void)file;
    HAL_UART_Transmit(&huart2, reinterpret_cast<uint8_t*>(ptr), static_cast<uint16_t>(len), HAL_MAX_DELAY);
    return len;
}

static void gpio_clocks_init(void) {
    __HAL_RCC_GPIOA_CLK_ENABLE();
    __HAL_RCC_GPIOB_CLK_ENABLE();
    __HAL_RCC_GPIOC_CLK_ENABLE();
}

static void uart2_init(void) {
    __HAL_RCC_USART2_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_2 | GPIO_PIN_3;  // PA2=TX, PA3=RX
    gpioInit.Mode      = GPIO_MODE_AF_PP;
    gpioInit.Pull      = GPIO_NOPULL;
    gpioInit.Speed     = GPIO_SPEED_FREQ_LOW;
    gpioInit.Alternate = GPIO_AF7_USART2;
    HAL_GPIO_Init(GPIOA, &gpioInit);

    huart2.Instance          = USART2;
    huart2.Init.BaudRate     = 115200;
    huart2.Init.WordLength   = UART_WORDLENGTH_8B;
    huart2.Init.StopBits     = UART_STOPBITS_1;
    huart2.Init.Parity       = UART_PARITY_NONE;
    huart2.Init.Mode         = UART_MODE_TX_RX;
    huart2.Init.HwFlowCtl    = UART_HWCONTROL_NONE;
    huart2.Init.OverSampling = UART_OVERSAMPLING_16;
    HAL_UART_Init(&huart2);
}

static void i2c1_init(void) {
    __HAL_RCC_I2C1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_8 | GPIO_PIN_9;  // PB8=SCL, PB9=SDA
    gpioInit.Mode      = GPIO_MODE_AF_OD;
    gpioInit.Pull      = GPIO_PULLUP;               // internal pull-up, in case the breakout has none
    gpioInit.Speed     = GPIO_SPEED_FREQ_HIGH;
    gpioInit.Alternate = GPIO_AF4_I2C1;
    HAL_GPIO_Init(GPIOB, &gpioInit);

    hi2c1.Instance             = I2C1;
    hi2c1.Init.ClockSpeed      = 100000;
    hi2c1.Init.DutyCycle       = I2C_DUTYCYCLE_2;
    hi2c1.Init.OwnAddress1     = 0;
    hi2c1.Init.AddressingMode  = I2C_ADDRESSINGMODE_7BIT;
    hi2c1.Init.DualAddressMode = I2C_DUALADDRESS_DISABLE;
    hi2c1.Init.OwnAddress2     = 0;
    hi2c1.Init.GeneralCallMode = I2C_GENERALCALL_DISABLE;
    hi2c1.Init.NoStretchMode   = I2C_NOSTRETCH_DISABLE;
    HAL_I2C_Init(&hi2c1);
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, Isl29125Minimal::I2C_ADDRESS);
    Isl29125Full sensor(connection);                                // Create ISL29125 driver, (connection)
    printf("device id 0x%02X\r\n", (unsigned)(sensor.deviceId()));  // Read DEVICE_ID, () → uint8_t
                                                                    // 0x7D for the ISL29125
    Isl29125Counts c = {0, 0, 0};
    sensor.read(c);                                                 // Read the three channels, (counts&) → bool
    printf("R %lu G %lu B %lu\r\n", (unsigned long)(c.red), (unsigned long)(c.green), (unsigned long)(c.blue));
    printf("red %lu\r\n", (unsigned long)(sensor.readRed()));       // Read the red channel, () → uint16_t
    printf("green %lu\r\n", (unsigned long)(sensor.readGreen()));   // Read the green channel, () → uint16_t
    printf("blue %lu\r\n", (unsigned long)(sensor.readBlue()));     // Read the blue channel, () → uint16_t
    sensor.readRgb(c);                                              // Block-read without waiting, (counts&) → void
    printf("ready %lu\r\n", (unsigned long)(sensor.dataReady()));   // Query CONVENF, () → bool
                                                                    // clears the status flags on the chip
    bool ok = sensor.setMode(Isl29125Full::MODE_GREEN);             // Select the channels, (mode) → bool
                                                                    // MODE_POWER_DOWN, _GREEN, _RED, _BLUE, _STANDBY, _RGB, _GREEN_RED, _GREEN_BLUE
    ok = sensor.setMode(Isl29125Full::MODE_RGB);                    // Back to all three channels, (mode) → bool
    ok = sensor.setRange(375);                                      // Set the full-scale range, (lux) → bool
                                                                    // 375 or 10000
    ok = sensor.setRange(10000);
    ok = sensor.setResolution(12);                                  // Set the ADC resolution, (bits) → bool
                                                                    // 12 or 16
    ok = sensor.setResolution(16);
    sensor.setSync(false);                                          // Select the conversion start, (external) → void
                                                                    // true: rising edge of INT starts the conversion
    ok = sensor.setIrCompensation(true, 0x3F);                      // Set the IR compensation, (offset, adjust) → bool
                                                                    // adjust 0-63; offset selects codes 106-169
    ok = sensor.setThresholds(1000, 50000);                         // Set the threshold window, (low, high) → bool
    uint16_t lo = 0, hi = 0;
    sensor.getThresholds(lo, hi);                                   // Read the window, (low&, high&) → void
    printf("window %lu - %lu\r\n", (unsigned long)(lo), (unsigned long)(hi));
    ok = sensor.setInterruptChannel(Isl29125Full::CHANNEL_GREEN);   // Choose the monitored channel, (channel) → bool
                                                                    // CHANNEL_NONE, _GREEN, _RED or _BLUE
    ok = sensor.setPersistence(2);                                  // Set the persistence, (count) → bool
                                                                    // 1, 2, 4 or 8 integration cycles
    sensor.enableInterrupt(Isl29125Full::SOURCE_THRESHOLD);         // Enable the interrupt, (source) → void
                                                                    // SOURCE_THRESHOLD or SOURCE_CONVERSION
    HAL_Delay(2000);
    printf("poll 0x%02X\r\n", (unsigned)(sensor.pollInterrupt()));  // Read and clear STATUS, () → uint8_t
                                                                    // 0x04 brown-out, 0x02 conversion, 0x01 threshold
    sensor.disableInterrupt(Isl29125Full::SOURCE_THRESHOLD);        // Disable the interrupt, (source) → void
    printf("status 0x%02X\r\n", (unsigned)(sensor.status()));       // Raw STATUS, () → uint8_t
    sensor.setMode(Isl29125Full::MODE_POWER_DOWN);
    sensor.softReset();                                             // Software reset, () → void
                                                                    // all registers to defaults
    printf("ok %lu\r\n", (unsigned long)(ok));
    while (1) {}
}
