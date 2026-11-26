#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Si1133.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x55);
    Si1133Full sensor(connection);

    CHECK(sensor.init(), "init");                          // Check PART_ID, reset, UV + white channels, () → bool
    uint8_t part = 0, hw = 0, rev = 0;
    CHECK(sensor.read_id(part, hw, rev) && part == 0x33, "part_id");
    CHECK((hw & 0x1F) == 0x03, "hw_id_impl");
    uint8_t state = 0, counter = 0;
    bool err = true;
    CHECK(sensor.read_response(state, err, counter) && !err, "response_no_error");
    int32_t uv = 0, white = 0;
    CHECK(sensor.read(uv, white), "read_ok");             // Forced measurement, (uv&, white&) → bool
    NEAR(uv, -8388608L, 8388607L, "read_uv_range");
    CHECK(white > 0, "read_white_positive_in_light");
    int32_t x = 0;
    CHECK(sensor.read_uv(x), "read_uv_scratch");
    CHECK(sensor.read_white(x) && x > 0, "read_white_scratch");
    CHECK(sensor.read_ir(x, Si1133Full::MUX_SMALL_IR), "read_ir_small");
    CHECK(sensor.read_ir(x, Si1133Full::MUX_LARGE_IR), "read_ir_large");
    CHECK(sensor.configure_channel(2, Si1133Full::MUX_LARGE_IR, 5, 0, 0, false, false, 0), "configure_channel");
    CHECK(sensor.enable_channels(0x07), "enable_channels");
    int32_t v[6] = {0, 0, 0, 0, 0, 0};
    size_t n = 0;
    CHECK(sensor.read_channels(v, n) && n == 3, "read_channels_count");
    NEAR(v[2], 0L, 65535L, "read_channels_16bit");
    uint8_t p = 0;
    CHECK(sensor.write_parameter(0x1C, 0x05) && sensor.read_parameter(0x1C, p) && p == 0x05, "parameter_roundtrip");
    CHECK(sensor.read_parameter(0x01, p) && p == 0x07, "chan_list_readback");
    CHECK(sensor.set_threshold(0, 1000) && sensor.set_channel_threshold(0, 1), "threshold");
    uint8_t hi = 0, lo = 0;
    CHECK(sensor.read_parameter(0x25, hi) && sensor.read_parameter(0x26, lo) && hi == 0x03 && lo == 0xE8, "threshold_readback");
    sensor.set_channel_threshold(0, 0);
    sensor.enable_channels(0x03);
    sensor.poll_interrupt();
    CHECK(sensor.start_autonomous(0.1f), "start_autonomous");
    TEST_DELAY_MS(500);
    CHECK((sensor.poll_interrupt() & 0x03) != 0, "autonomous_irq");
    CHECK(sensor.read_channels(v, n, false) && n == 2, "autonomous_values");
    CHECK(sensor.pause(), "pause");
    CHECK(sensor.reset(), "reset");
    CHECK(sensor.read_response(state, err, counter) && (state & Si1133Full::RESP_SLEEP), "reset_sleep");
    CHECK(sensor.read(uv, white) && white > 0, "read_after_reset");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
