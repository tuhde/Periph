#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include <math.h>
#include "Spl06.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x77);
    SPL06Full sensor(connection);

    CHECK(sensor.init(), "init");                          // Check ID, read coefficients, apply defaults, () → bool
    SPL06Coefficients co;
    sensor.coefficients(co);
    CHECK(co.c0 != 0 || co.c1 != 0 || co.c00 != 0 || co.c10 != 0, "coefficients_decoded");
    float t = 0, p = 0, alt = 0;
    CHECK(sensor.read(t, p), "read_ok");                   // Measure temperature and pressure, (temperature_c&, pressure_pa&) → bool
    NEAR(p, 30000.0f, 110000.0f, "read_pressure_range");
    NEAR(t, -40.0f, 85.0f, "read_temperature_range");
    CHECK(sensor.read_pressure(p) && p >= 30000.0f && p <= 110000.0f, "read_pressure_single");
    CHECK(sensor.read_temperature(t) && t > -40.0f && t < 85.0f, "read_temperature_single");
    int32_t praw = 0, traw = 0;
    CHECK(sensor.read_raw(praw, traw), "read_raw");
    CHECK(sensor.compensate(praw, traw, 16, 1, t, p) && p >= 30000.0f && p <= 110000.0f, "compensate_raw");
    CHECK(sensor.altitude(alt) && alt > -500.0f && alt < 9000.0f, "altitude_range");
    static const uint8_t osrs[3] = {1, 8, 128};
    for (uint8_t i = 0; i < 3; i++) {
        sensor.set_pressure_config(osrs[i], 1);
        CHECK(sensor.read_pressure(p) && p >= 30000.0f && p <= 110000.0f, "read_at_osr");
    }
    sensor.set_pressure_config(16, 8);
    sensor.set_temperature_config(1, 8);
    CHECK(sensor.start_continuous(), "start_continuous");
    CHECK(sensor.read_continuous(t, p) && p >= 30000.0f && p <= 110000.0f, "read_continuous");
    TEST_DELAY_MS(300);
    bool pr = false, tr = false;
    CHECK(sensor.data_ready(pr, tr) && pr, "data_ready");
    CHECK(sensor.stop(), "stop");
    sensor.flush_fifo();
    sensor.enable_fifo();
    sensor.start_continuous();
    TEST_DELAY_MS(1000);
    sensor.stop();
    bool empty = true, full = false;
    CHECK(sensor.fifo_status(empty, full) && !empty, "fifo_not_empty");
    static SPL06Full::FifoEntry entries[32];
    uint8_t n = sensor.read_fifo(entries, 32);
    CHECK(n >= 4, "fifo_read");
    bool values_ok = true;
    for (uint8_t i = 0; i < n; i++) {
        if (entries[i].kind == SPL06Full::KIND_PRESSURE && (entries[i].value < 30000.0f || entries[i].value > 110000.0f))
            values_ok = false;
    }
    CHECK(values_ok, "fifo_values");
    CHECK(sensor.fifo_status(empty, full) && empty, "fifo_empty_after_read");
    sensor.disable_fifo();
    sensor.enable_interrupt(SPL06Full::SOURCE_PRESSURE);
    sensor.poll_interrupt();
    sensor.read(t, p);
    CHECK((sensor.poll_interrupt() & SPL06Full::SOURCE_PRESSURE) != 0, "poll_interrupt_pressure");
    sensor.disable_interrupt(SPL06Full::SOURCE_PRESSURE);
    CHECK(sensor.reset(), "reset");
    CHECK(sensor.read_pressure(p) && p >= 30000.0f && p <= 110000.0f, "read_after_reset");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
