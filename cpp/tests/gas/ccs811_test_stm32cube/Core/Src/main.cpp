#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Ccs811.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x5A);
    CCS811Full sensor(connection);

    CHECK(sensor.init(), "init");                          // Check HW_ID, start application, mode 1, () → bool
    uint8_t hw_id = 0, hw_version = 0, st = 0, err = 0;
    CHECK(sensor.hardware_version(hw_id, hw_version) && hw_id == 0x81, "hardware_id");
    CHECK((hw_version & 0xF0) == 0x10, "hardware_version");
    CCS811Version boot = {0, 0, 0}, app = {0, 0, 0};
    CHECK(sensor.firmware_version(boot, app) && app.major >= 1, "firmware_version");
    CHECK(sensor.status(st) && (st & CCS811Full::STATUS_FW_MODE), "status_app_mode");
    CHECK(!(st & CCS811Full::STATUS_ERROR), "status_no_error");
    uint16_t eco2 = 0, tvoc = 0;
    CHECK(sensor.read(eco2, tvoc), "read_ok");             // Next sample, (eco2_ppm&, tvoc_ppb&) → bool
    NEAR(eco2, 400, 8192, "read_eco2_range");
    NEAR(tvoc, 0, 1187, "read_tvoc_range");
    CHECK(sensor.read_eco2(eco2) && eco2 >= 400 && eco2 <= 8192, "read_eco2_single");
    CHECK(sensor.read_tvoc(tvoc) && tvoc <= 1187, "read_tvoc_single");
    uint8_t current = 0;
    float voltage = 0;
    CHECK(sensor.read_raw(current, voltage) && current <= 63, "read_raw_current");
    NEAR(voltage, 0.0f, 1.65f, "read_raw_voltage");
    bool ready = true;
    CHECK(sensor.data_ready(ready) && !ready, "data_ready_cleared");
    CHECK(sensor.set_environment(50.0f, 25.0f), "set_environment");
    CHECK(sensor.set_thresholds(1000, 2000, 50), "set_thresholds");
    uint16_t v_ref = 0, v_ntc = 0;
    CHECK(sensor.read_ntc(v_ref, v_ntc), "read_ntc");
    uint16_t baseline = 0;
    CHECK(sensor.get_baseline(baseline) && sensor.set_baseline(baseline), "baseline_roundtrip");
    CHECK(sensor.error(err) && err == 0, "no_error");
    sensor.enable_interrupt(CCS811Full::SOURCE_DATA_READY);
    TEST_DELAY_MS(1200);
    CHECK((sensor.poll_interrupt() & CCS811Full::STATUS_DATA_READY) != 0, "poll_interrupt_data_ready");
    sensor.read(eco2, tvoc);
    sensor.disable_interrupt(CCS811Full::SOURCE_DATA_READY);
    CHECK(sensor.set_mode(4), "set_mode_4");
    TEST_DELAY_MS(600);
    CHECK(sensor.read_raw(current, voltage) && current <= 63, "mode_4_raw");
    sensor.set_mode(0);
    sensor.set_mode(1);
    CHECK(sensor.soft_reset(), "soft_reset");
    CHECK(sensor.status(st) && (st & CCS811Full::STATUS_FW_MODE), "status_after_reset");
    CHECK(sensor.read(eco2, tvoc) && eco2 >= 400 && eco2 <= 8192, "read_after_reset");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
