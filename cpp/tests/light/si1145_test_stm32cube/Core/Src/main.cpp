#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Si1145.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x60);
    Si1145Full sensor(connection);

    CHECK(sensor.init(), "init");                          // Check PART_ID, HW_KEY, UV + ALS + PS1, () → bool
    uint8_t part = 0, rev = 0, seq = 0;
    CHECK(sensor.read_id(part, rev, seq) && part >= 0x45 && part <= 0x47, "part_id");
    uint8_t counter = 0, err = 0xFF;
    CHECK(sensor.read_response(counter, err) && err == 0, "response_no_error");
    Si1145Reading r;
    CHECK(sensor.read(r), "read_ok");                      // Forced PS + ALS, (reading&) → bool
    CHECK(r.uv_index >= 0.0f && r.uv_index < 20.0f, "read_uv_index_range");
    CHECK(r.visible > 0, "read_visible_positive");
    CHECK(r.infrared > 0, "read_infrared_positive");
    uint16_t v = 0;
    float uv = 0.0f;
    CHECK(sensor.read_proximity(v), "read_proximity_single");
    CHECK(sensor.read_uv_index(uv) && uv < 20.0f, "read_uv_index_single");
    CHECK(sensor.read_visible(v) && v > 0, "read_visible_single");
    CHECK(sensor.read_infrared(v) && v > 0, "read_infrared_single");
    Si1145Raw raw;
    CHECK(sensor.read_raw(raw), "read_raw");
    CHECK(sensor.read_temperature_raw(v) && v > 0, "read_temperature_raw");
    CHECK(sensor.read_vdd_raw(v) && v > 0, "read_vdd_raw");
    uint16_t dark = 0, lit = 0;
    CHECK(sensor.set_led_current(1, 0) && sensor.read_proximity(dark), "led_off_read");
    CHECK(sensor.set_led_current(1, 15) && sensor.read_proximity(lit), "led_max_read");
    CHECK(lit >= dark, "led_current_effect");
    CHECK(sensor.set_led_current(1, 3), "led_default");
    CHECK(!sensor.set_led_current(1, 16), "led_level_invalid");
    CHECK(sensor.set_ps_photodiode(1, true), "set_ps_photodiode");
    CHECK(sensor.set_gain(2, 2, 0) && sensor.read_visible(v) && v > 0, "gain_read");
    CHECK(sensor.set_gain(), "gain_default");
    CHECK(sensor.set_high_range(true, true, false) && sensor.read_visible(v), "high_range_read");
    CHECK(sensor.set_high_range(false, false, false), "high_range_off");
    CHECK(sensor.set_alignment(), "set_alignment");
    const uint8_t ucoef[4] = {0x7B, 0x6B, 0x01, 0x00};
    CHECK(sensor.set_uv_coefficients(ucoef), "set_uv_coefficients");
    uint8_t cal[12];
    CHECK(sensor.read_calibration(cal), "read_calibration");
    CHECK(sensor.set_channels(true, true, true, true), "set_channels");
    sensor.poll_interrupt();
    CHECK(sensor.start_autonomous(0.05f), "start_autonomous");
    TEST_DELAY_MS(500);
    CHECK((sensor.poll_interrupt() & 0x05) != 0, "autonomous_irq");
    CHECK(sensor.pause(), "pause");
    CHECK(sensor.force(), "force");
    TEST_DELAY_MS(100);
    CHECK(sensor.poll_interrupt() != 0, "force_irq");
    CHECK(sensor.enable_interrupt(Si1145Full::SOURCE_CMD), "enable_interrupt");
    CHECK(sensor.disable_interrupt(Si1145Full::SOURCE_CMD), "disable_interrupt");
    CHECK(sensor.reset(), "reset");
    CHECK(sensor.read_response(counter, err) && err == 0, "reset_no_error");
    CHECK(sensor.read(r) && r.visible > 0, "read_after_reset");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
