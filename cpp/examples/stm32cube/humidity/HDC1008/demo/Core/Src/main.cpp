#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "HDC1008.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x40);
    HDC1008Full sensor(connection);                                     // Create HDC1008 driver, (connection) → void
    float t = 0, rh = 0;

    uint64_t serial = 0;
    sensor.init();                                                      // Write default configuration, () → void
    serial = sensor.serial_id();                                        // Read the 40-bit serial ID, () → uint64_t
    printf("serial id 0x%02X%08X\r\n", (unsigned)((serial) >> 32), (unsigned)((serial) & 0xFFFFFFFF));

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    for (uint8_t i = 0; i < 15; i++) {
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        float g = logf(fmaxf(rh, 0.01f) / 100.0f) + 17.62f * t / (243.12f + t);
        float dew = 243.12f * g / (17.62f - g);                         // Magnus dew point, °C
        printf("%.2f C %.1f %%RH dew point %.2f C\r\n", t, rh, dew);
        HAL_Delay(2000);
    }

    // --- Heater test: 10 s ---
    float rh0 = sensor.read_humidity();
    float t0c = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    HAL_Delay(10000);
    float rh1 = sensor.read_humidity();
    float t1c = sensor.read_temperature();
    printf("RH drop %.1f %%RH, temperature rise %.2f K\r\n", rh0 - rh1, t1c - t0c);
    sensor.set_heater(false);                                           // Heater off

    // --- Resolution comparison ---
    sensor.set_resolution(11, 8);                                       // Set resolution, (temperature_bits, humidity_bits) → bool
    uint32_t ts = HAL_GetTick();
    sensor.read(t, rh);
    uint32_t dt = HAL_GetTick() - ts;
    printf("11/8 bit: %.2f C %.1f %%RH in %u ms\r\n", t, rh, (unsigned)(dt));
    sensor.set_resolution(14, 14);                                      // Back to 14/14 bit
    printf("battery low %u\r\n", (unsigned)(sensor.battery_low()));
    // sensor.battery_low() → bool, true when VDD is below 2.8 V
    while (true) HAL_Delay(1000);
}
