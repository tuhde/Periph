#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "As7262.h"

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

static void on_data(uint8_t status) {
    printf("data ready %lu\r\n", (unsigned long)(status));
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x49);
    AS7262Full sensor(connection);                                  // Create AS7262 driver, (connection) → void

    AS7262Channels base = {}, lit = {}, ch = {};
    AS7262RawChannels raw = {};
    uint8_t u8a = 0, u8b = 0, u8c = 0;
    sensor.begin();                                                 // Reset and configure defaults, () → bool
    // --- Spectrometer ---
    // Versions, gain sweep, integration time comparison, LED reflectance difference, continuous mode.
    sensor.hardware_version(u8a, u8b);                              // Hardware version, (type, version) → bool
    sensor.firmware_version(u8a, u8b, u8c);                         // Firmware version, (major, minor, sub) → bool
    printf("type 0x%lX hw %lu fw %lu\r\n", (unsigned long)(sensor.device_type()), (unsigned long)(u8a), (unsigned long)(u8c));
    printf("chip %.0f C\r\n", (double)(sensor.temperature()));
    static const float gains[4] = {1.0f, 3.7f, 16.0f, 64.0f};
    for (int i = 0; i < 4; i++) {
        sensor.set_gain(gains[i]);                                  // Set gain, (gain) → bool
        sensor.read(ch);                                            // Read calibrated, (ch) → bool
        sensor.read_raw(raw);                                       // Read raw, (raw) → bool
        printf("gain %.1f V %.2f raw V %lu\r\n", (double)(sensor.get_gain()), (double)(ch.violet), (unsigned long)(raw.violet));
    }
    sensor.set_gain(16);                                            // Set gain, (gain) → bool
    static const float times[2] = {50.0f, 500.0f};
    for (int i = 0; i < 2; i++) {
        sensor.set_integration_time(times[i]);                      // Set integration time, (ms) → bool
        sensor.read(ch);                                            // One-shot read, (ch) → bool
        printf("integration %.1f ms\r\n", (double)(sensor.get_integration_time()));
    }
    sensor.set_integration_time(165.2f);                            // Set integration time, (ms) → bool
    sensor.set_mode(3);                                             // Set bank mode, (mode) → bool
    sensor.read(base);                                              // Read calibrated, (ch) → bool
    sensor.set_indicator_led(true, 4.0f);                           // Indicator LED, (on, current_ma) → bool
    sensor.set_led(true, 25.0f);                                    // Driver LED, (on, current_ma) → bool
    sensor.read(lit);                                               // Read calibrated, (ch) → bool
    sensor.set_led(false);                                          // Driver LED off, (on, current_ma) → bool
    sensor.set_indicator_led(false);                                // Indicator LED off, (on, current_ma) → bool
    const float* b = &base.violet; const float* l = &lit.violet; int best = 0;
    for (int i = 1; i < 6; i++) { if (l[i] - b[i] > l[best] - b[best]) best = i; }
    printf("reflectance dominant channel %lu\r\n", (unsigned long)(best));
    sensor.measure();                                               // Start one-shot, () → bool
    while (!sensor.data_ready()) { HAL_Delay(50); }                 // Data ready, () → bool
    sensor.read_calibrated(ch);                                     // Last calibrated results, (ch) → bool
    sensor.read_raw_last(raw);                                      // Last raw counts, (raw) → bool
    printf("last V %.2f raw V %lu\r\n", (double)(sensor.read_channel(0)), (unsigned long)(raw.violet));
    sensor.set_continuous(2);                                       // Continuous mode, (mode) → bool
    sensor.on_interrupt(on_data);                                   // Subscribe to INT, (callback, intPin=nullptr) → void
    for (int i = 0; i < 10; i++) {
        HAL_Delay(500);
        if (sensor.poll_interrupt()) { sensor.read_calibrated(ch); }
        printf("V %.2f\r\n", (double)(ch.violet));
    }
    sensor.off_interrupt();                                         // Unsubscribe, () → void
    sensor.set_mode(3);                                             // Back to one-shot, (mode) → bool
    sensor.write_virtual(0x05, 0x3B);                               // Write virtual register, (reg, value) → bool
    sensor.read_virtual(0x05, u8a);                                 // Read virtual register, (reg, value) → bool
    sensor.soft_reset();                                            // Soft reset, () → bool
    while (true) { HAL_Delay(1000); }
}
