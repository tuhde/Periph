#include <stdio.h>
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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x49);
    AS7262Full sensor(connection);

    AS7262Channels ch = {};
    AS7262RawChannels raw = {};
    uint8_t u8a = 0, u8b = 0;
    uint16_t u16 = 0;

    CHECK(sensor.begin(), "begin");
    CHECK(sensor.device_type() == 0x40, "device type");
    CHECK(sensor.hardware_version(u8a, u8b) && u8a == 0x40 && u8b == 0x3E, "hardware version");
    NEAR(sensor.temperature(), -40.0f, 125.0f, "temperature range");

    CHECK(sensor.read(ch), "read ok");
    CHECK(ch.violet >= 0.0f && ch.blue >= 0.0f && ch.green >= 0.0f && ch.yellow >= 0.0f && ch.orange >= 0.0f && ch.red >= 0.0f, "calibrated channels");
    CHECK(sensor.read_raw(raw), "read_raw ok");
    CHECK(sensor.read_channel_raw(2, u16), "read_channel_raw");

    CHECK(sensor.set_gain(64), "set_gain 64");
    CHECK(sensor.get_gain() == 64.0f, "gain readback");
    CHECK(sensor.set_gain(16), "set_gain 16");
    CHECK(sensor.set_integration_time(50.0f), "set_integration_time");
    NEAR(sensor.get_integration_time(), 50.3f, 50.5f, "integration time readback");
    CHECK(sensor.set_mode(2), "set_mode 2");
    CHECK(sensor.get_mode() == 2, "mode readback");
    CHECK(sensor.set_mode(3), "set_mode 3");

    CHECK(sensor.measure(), "measure");
    TEST_DELAY_MS(600);
    CHECK(sensor.data_ready(), "data ready");
    CHECK(sensor.read_calibrated(ch), "read_calibrated");

    CHECK(sensor.set_indicator_led(true, 4.0f), "indicator led on");
    CHECK(sensor.set_indicator_led(false), "indicator led off");
    CHECK(sensor.soft_reset(), "soft_reset");
    NEAR(sensor.get_integration_time(), 700.0f, 714.0f, "reset int_t");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
