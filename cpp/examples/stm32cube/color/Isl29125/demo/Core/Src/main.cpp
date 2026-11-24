#include <math.h>
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

    // --- Colour sampler ---
    // Prints the device ID, samples R/G/B as a normalised colour, compares the
    // 375 lux and 10 000 lux ranges and the 12/16-bit resolutions, varies the IR
    // compensation, reads green only, arms a threshold interrupt on green with
    // persistence 2 (polled through pollInterrupt), then powers down and resets.
    printf("device id 0x%02X\r\n", (unsigned)(sensor.deviceId()));  // Read DEVICE_ID, () → uint8_t
    Isl29125Counts c = {0, 0, 0};
    for (int i = 0; i < 20; i++) {
        sensor.read(c);                                             // Read the three channels, (counts&) → bool
        uint16_t top = c.red > c.green ? c.red : c.green;
        if (c.blue > top) top = c.blue;
        if (top == 0) top = 1;
        printf("#%02X%02X%02X\r\n", (unsigned)((unsigned long)c.red * 255 / top), (unsigned)((unsigned long)c.green * 255 / top), (unsigned)((unsigned long)c.blue * 255 / top));
        HAL_Delay(500);
    }
    const uint16_t ranges[2] = {375, 10000};
    for (uint8_t i = 0; i < 2; i++) {
        sensor.setRange(ranges[i]);                                 // Set the range, (lux) → bool
        HAL_Delay(400);
        sensor.readRgb(c);                                          // Read without waiting, (counts&) → void
        printf("%lu lux range G %lu R %lu B %lu\r\n", (unsigned long)(ranges[i]), (unsigned long)(c.green), (unsigned long)(c.red), (unsigned long)(c.blue));
    }
    const uint8_t bits[2] = {12, 16};
    for (uint8_t i = 0; i < 2; i++) {
        sensor.setResolution(bits[i]);                              // Set the resolution, (bits) → bool
        HAL_Delay(400);
        sensor.readRgb(c);
        printf("%lu bit full scale %lu G %lu\r\n", (unsigned long)(bits[i]), (unsigned long)((1UL << bits[i]) - 1), (unsigned long)(c.green));
    }
    sensor.setRange(10000);
    const uint8_t adjust[3] = {0, 0x20, 0x3F};
    for (uint8_t i = 0; i < 3; i++) {
        sensor.setIrCompensation(false, adjust[i]);                 // Set the IR compensation, (offset, adjust) → bool
        HAL_Delay(400);
        sensor.readRgb(c);
        printf("IR compensation %lu G %lu R %lu B %lu\r\n", (unsigned long)(adjust[i]), (unsigned long)(c.green), (unsigned long)(c.red), (unsigned long)(c.blue));
    }
    sensor.setIrCompensation(true, 0x3F);
    sensor.setMode(Isl29125Full::MODE_GREEN);                       // Green only, (mode) → bool
    HAL_Delay(300);
    printf("green only %lu\r\n", (unsigned long)(sensor.readGreen()));// Read green, () → uint16_t
    sensor.setMode(Isl29125Full::MODE_RGB);
    printf("ready %lu\r\n", (unsigned long)(sensor.dataReady()));   // Query CONVENF, () → bool
    uint16_t base = sensor.readGreen();
    uint16_t lo = base - base / 4;
    uint16_t hi = base + base / 4 > 0xFFFF ? 0xFFFF : base + base / 4;
    sensor.setThresholds(lo, hi);                                   // Set the window, (low, high) → bool
    sensor.getThresholds(lo, hi);                                   // Read the window, (low&, high&) → void
    printf("window %lu - %lu\r\n", (unsigned long)(lo), (unsigned long)(hi));
    sensor.setInterruptChannel(Isl29125Full::CHANNEL_GREEN);        // Choose the channel, (channel) → bool
    sensor.setPersistence(2);                                       // Set the persistence, (count) → bool
    sensor.enableInterrupt(Isl29125Full::SOURCE_THRESHOLD);         // Enable the interrupt, (source) → void
    for (int i = 0; i < 20; i++) {
        HAL_Delay(500);
        uint8_t st = sensor.pollInterrupt();                        // Read and clear STATUS, () → uint8_t
        if (st & Isl29125Full::STATUS_THRESHOLD) {
            printf("window crossed, green %lu\r\n", (unsigned long)(sensor.readGreen()));
        }
    }
    sensor.disableInterrupt(Isl29125Full::SOURCE_THRESHOLD);        // Disable the interrupt, (source) → void
    printf("status 0x%02X\r\n", (unsigned)(sensor.status()));
    sensor.setMode(Isl29125Full::MODE_POWER_DOWN);
    sensor.softReset();                                             // Software reset, () → void
    while (1) {}
}
