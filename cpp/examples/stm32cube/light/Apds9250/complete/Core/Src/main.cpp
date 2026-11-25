#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Apds9250.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, Apds9250Minimal::I2C_ADDRESS);
    Apds9250Full sensor(connection);                                // Create APDS-9250 driver, (connection)
    uint8_t part = 0, rev = 0;
    sensor.partId(part, rev);                                       // Read PART_ID, (part&, revision&) → void
                                                                    // 0xB, 0x5 for the APDS-9250
    printf("part %02X rev %02X\r\n", (unsigned)(part), (unsigned)(rev));
    Apds9250Counts c = {0, 0, 0, 0};
    sensor.read(c);                                                 // Read the four channels, (counts&) → bool
    printf("R %lu G %lu B %lu IR %lu\r\n", (unsigned long)(c.red), (unsigned long)(c.green), (unsigned long)(c.blue), (unsigned long)(c.ir));
    printf("red %lu\r\n", (unsigned long)(sensor.readRed()));       // Read the red channel, () → uint32_t
    printf("green %lu\r\n", (unsigned long)(sensor.readGreen()));   // Read the green / ALS channel, () → uint32_t
    printf("blue %lu\r\n", (unsigned long)(sensor.readBlue()));     // Read the blue channel, () → uint32_t
    printf("ir %lu\r\n", (unsigned long)(sensor.readIr()));         // Read the IR channel, () → uint32_t
    printf("ready %lu\r\n", (unsigned long)(sensor.dataReady()));   // Query LS_DATA, () → bool
                                                                    // clears the status flags on the chip
    sensor.readChannels(c);                                         // Block-read without waiting, (counts&) → void
    sensor.setMode(false);                                          // Select ALS/IR mode, (colour) → void
                                                                    // false: only green and IR are active
    sensor.setMode(true);                                           // Back to colour mode, (colour) → void
    sensor.disable();                                               // Standby, () → void
    sensor.enable();                                                // Activate the sensor, () → void
    bool ok = sensor.setResolution(16);                             // Set the ADC resolution, (bits) → bool
                                                                    // 13, 16, 17, 18, 19 or 20; restarts the measurement
    ok = sensor.setMeasurementRate(200);                            // Set the measurement rate, (milliseconds) → bool
                                                                    // 25, 50, 100, 200, 500, 1000 or 2000
    ok = sensor.setGain(6);                                         // Set the gain, (gain) → bool
                                                                    // 1, 3, 6, 9 or 18; restarts the measurement
    printf("resolution %lu bit\r\n", (unsigned long)(sensor.getResolution()));// Read the resolution, () → uint8_t
    printf("rate %lu ms\r\n", (unsigned long)(sensor.getMeasurementRate()));// Read the rate, () → uint16_t
    printf("gain %lu\r\n", (unsigned long)(sensor.getGain()));      // Read the gain, () → uint8_t
    printf("integration %.3f s\r\n", (double)(sensor.integrationTime()));// Integration time, () → float s
    printf("normalized %.0f\r\n", (double)(sensor.normalized(sensor.readGreen())));// Counts per gain-second, (counts) → float
    ok = sensor.setThresholds(1000, 100000);                        // Set the threshold window, (low, high) → bool
                                                                    // 20-bit counts
    uint32_t lo = 0, hi = 0;
    sensor.getThresholds(lo, hi);                                   // Read the window, (low&, high&) → void
    printf("window %lu - %lu\r\n", (unsigned long)(lo), (unsigned long)(hi));
    ok = sensor.setInterruptChannel(Apds9250Full::CHANNEL_GREEN);   // Choose the monitored channel, (channel) → bool
                                                                    // CHANNEL_IR, _GREEN, _RED or _BLUE
    ok = sensor.setPersistence(2);                                  // Set the persistence, (count) → bool
                                                                    // 1–16 consecutive events
    ok = sensor.setVariance(64);                                    // Set the variation threshold, (counts) → bool
                                                                    // 8 … 1024, power of two
    sensor.enableInterrupt(Apds9250Full::SOURCE_VARIATION);         // Enable the interrupt, (source) → void
                                                                    // SOURCE_THRESHOLD or SOURCE_VARIATION
    HAL_Delay(2000);
    printf("poll 0x%02X\r\n", (unsigned)(sensor.pollInterrupt()));  // Read and clear MAIN_STATUS, () → uint8_t
                                                                    // 0x20 power-on, 0x10 interrupt, 0x08 data
    sensor.disableInterrupt();                                      // Disable the interrupt, () → void
    printf("status 0x%02X\r\n", (unsigned)(sensor.status()));       // Raw MAIN_STATUS, () → uint8_t
    sensor.softReset();                                             // Software reset, () → void
                                                                    // all registers to defaults, sensor in standby
    printf("ok %lu\r\n", (unsigned long)(ok));
    while (1) {}
}
