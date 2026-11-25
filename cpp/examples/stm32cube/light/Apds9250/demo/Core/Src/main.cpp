#include <math.h>
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

    // --- Colour sampler ---
    // Prints the part ID and settings, samples R/G/B/IR as a normalised colour,
    // compares ALS/IR mode, steps gain and resolution with normalised counts,
    // arms a variation and a threshold interrupt on the green channel (polled
    // through pollInterrupt), then disables the sensor and soft-resets it.
    uint8_t part = 0, rev = 0;
    sensor.partId(part, rev);                                       // Read PART_ID, (part&, revision&) → void
    printf("part %02X rev %02X resolution %lu bit rate %lu ms gain %lu\r\n", (unsigned)(part), (unsigned)(rev), (unsigned long)(sensor.getResolution()), (unsigned long)(sensor.getMeasurementRate()), (unsigned long)(sensor.getGain()));
    Apds9250Counts c = {0, 0, 0, 0};
    for (int i = 0; i < 20; i++) {
        sensor.read(c);                                             // Read the four channels, (counts&) → bool
        uint32_t top = c.red > c.green ? c.red : c.green;
        if (c.blue > top) top = c.blue;
        if (top == 0) top = 1;
        printf("#%02X%02X%02X IR %lu\r\n", (unsigned)((unsigned long)c.red * 255 / top), (unsigned)((unsigned long)c.green * 255 / top), (unsigned)((unsigned long)c.blue * 255 / top), (unsigned long)(c.ir));
        HAL_Delay(500);
    }
    sensor.setMode(false);                                          // Select ALS/IR mode, (colour) → void
    printf("ALS/IR green %lu ir %lu\r\n", (unsigned long)(sensor.readGreen()), (unsigned long)(sensor.readIr()));
    sensor.setMode(true);                                           // Back to colour mode, (colour) → void
    const uint8_t gains[5] = {1, 3, 6, 9, 18};
    for (uint8_t i = 0; i < 5; i++) {
        sensor.setGain(gains[i]);                                   // Set the gain, (gain) → bool
        HAL_Delay(300);
        uint32_t g = sensor.readGreen();                            // Read green, () → uint32_t
        printf("gain %lu green %lu normalized %.0f\r\n", (unsigned long)(gains[i]), (unsigned long)(g), (double)(sensor.normalized(g)));
    }
    sensor.setGain(3);
    const uint8_t bits[6] = {13, 16, 17, 18, 19, 20};
    for (uint8_t i = 0; i < 6; i++) {
        sensor.setResolution(bits[i]);                              // Set the resolution, (bits) → bool
        HAL_Delay(500);
        uint32_t g = sensor.readGreen();
        printf("%lu bit full scale %lu green %lu normalized %.0f\r\n", (unsigned long)(bits[i]), (unsigned long)((1UL << bits[i]) - 1), (unsigned long)(g), (double)(sensor.normalized(g)));
    }
    sensor.setResolution(18);
    sensor.setMeasurementRate(100);                                 // Set the rate, (milliseconds) → bool
    sensor.setInterruptChannel(Apds9250Full::CHANNEL_GREEN);        // Choose the channel, (channel) → bool
    sensor.setPersistence(2);                                       // Set the persistence, (count) → bool
    sensor.setVariance(64);                                         // Set the variation threshold, (counts) → bool
    sensor.enableInterrupt(Apds9250Full::SOURCE_VARIATION);         // Enable the variation interrupt, (source) → void
    for (int i = 0; i < 20; i++) {
        HAL_Delay(500);
        uint8_t st = sensor.pollInterrupt();                        // Read and clear MAIN_STATUS, () → uint8_t
        if (st & Apds9250Full::STATUS_INTERRUPT) {
            printf("variation interrupt, green %lu\r\n", (unsigned long)(sensor.readGreen()));
        }
    }
    uint32_t base = sensor.readGreen();
    sensor.setThresholds(base - base / 4, base + base / 4);         // Set the window, (low, high) → bool
    sensor.enableInterrupt(Apds9250Full::SOURCE_THRESHOLD);         // Enable the threshold interrupt, (source) → void
    HAL_Delay(5000);
    printf("status 0x%02X\r\n", (unsigned)(sensor.pollInterrupt()));
    sensor.disableInterrupt();                                      // Disable the interrupt, () → void
    sensor.disable();                                               // Standby, () → void
    sensor.softReset();                                             // Software reset, () → void
    while (1) {}
}
