#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include <math.h>
#include "Bh1750.h"

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

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}
#define CHECK(c, l) check_true((c), (l))
#define NEAR(v, lo, hi, l) check_true(((v) >= (lo) && (v) <= (hi)), (l))
#define TEST_DELAY_MS(ms) HAL_Delay(ms)



int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x23);
    BH1750Full sensor(connection);

    sensor.init();                                          // Power on, () → void
    float lux = 0;
    uint16_t raw = 0;
    CHECK(sensor.read(lux), "read ok");                     // Measure illuminance, (lux&) → bool
    NEAR(lux, 0.0f, 120000.0f, "read_range");
    CHECK(sensor.read_raw(raw), "read_raw");
    CHECK(sensor.measure_once(BH1750Full::MODE_H, lux), "measure_once_h");
    CHECK(sensor.measure_once(BH1750Full::MODE_H2, lux), "measure_once_h2");
    CHECK(sensor.measure_once(BH1750Full::MODE_L, lux), "measure_once_l");
    CHECK(fabsf(sensor.lux_per_count() - 1.0f / 1.2f) < 1e-5f, "lux_per_count_default");
    CHECK(fabsf(sensor.measurement_time() - 0.180f) < 1e-5f, "measurement_time_default");
    CHECK(sensor.start_continuous(BH1750Full::MODE_H), "start_continuous");
    TEST_DELAY_MS(200);
    CHECK(sensor.read_continuous(lux), "read_continuous");
    NEAR(lux, 0.0f, 120000.0f, "read_continuous_range");
    CHECK(sensor.set_measurement_time(138), "set_measurement_time");
    CHECK(sensor.get_measurement_time_register() == 138, "mtreg_roundtrip");
    CHECK(fabsf(sensor.measurement_time() - 0.360f) < 1e-5f, "measurement_time_scaled");
    CHECK(sensor.measure_once(BH1750Full::MODE_H, lux), "read_with_mtreg");
    CHECK(sensor.set_sensitivity(1.0f), "set_sensitivity");
    CHECK(sensor.get_measurement_time_register() == 69, "sensitivity_default");
    sensor.power_down();
    sensor.power_on();
    sensor.reset();
    CHECK(sensor.measure_once(BH1750Full::MODE_H, lux), "read_after_reset");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
