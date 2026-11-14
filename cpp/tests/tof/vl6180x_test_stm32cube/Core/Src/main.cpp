#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "VL6180X.h"

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

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();

    I2CConnectionSTM32Cube connection(&hi2c1, VL6180XMinimal::I2C_ADDRESS, nullptr, nullptr, 2);

    VL6180XMinimal sensor(connection);                      // Create VL6180X driver, (connection)
    check_true(sensor.init(), "init");                      // Initialize, () → bool
    uint8_t mm = 0;
    check_true(sensor.read_range(mm) || sensor.last_error() != VL6180XMinimal::ERROR_TIMEOUT, "read_range_completes"); // Single-shot range, (uint8_t& mm) → bool

    VL6180XFull full(connection);                           // Create VL6180X Full driver, (connection)
    check_true(full.init(), "full_init");
    check_true(full.model_id() == 0xB4, "model_id");        // Read model ID, () → uint8_t
    uint8_t major = 0, minor = 0;
    full.revision(major, minor);                            // Model revision, (uint8_t& major, uint8_t& minor) → void
    check_true(major >= 1, "revision");
    uint8_t err = 0;
    check_true(full.read_range_status(mm, err) && err <= 15, "range_status"); // Range and error code, (uint8_t& mm, uint8_t& err) → bool
    uint16_t raw = 0;
    check_true(full.read_als_raw(raw), "als_raw");          // Single-shot ALS count, (uint16_t& raw) → bool
    check_true(full.read_lux() >= 0.0f, "lux_positive");    // Single-shot ALS in lux, () → float lux
    check_true(full.set_als_gain(0) && full.read_lux() >= 0.0f, "lux_gain_20"); // ALS gain code, (uint8_t code) → bool
    full.set_als_gain(6);
    check_true(full.set_als_integration_time(50) && full.read_lux() >= 0.0f, "lux_50ms"); // ALS integration time, (uint16_t ms) → bool
    full.set_als_integration_time(100);
    full.set_als_scaler(1);                                 // ALS result scaler, (uint8_t scaler) → bool

    full.set_range_offset(3);                               // Part-to-part offset, (int8_t mm) → void
    check_true(full.get_range_offset() == 3, "offset_roundtrip"); // Read the offset, () → int8_t mm
    full.set_range_offset(0);
    full.set_crosstalk_rate(0.0f);                          // Crosstalk rate, (float rate_mcps) → bool
    full.set_max_convergence_time(49);                      // Max convergence time, (uint8_t ms) → bool
    full.set_averaging_period(48);                          // Averaging period, (uint8_t units) → void
    check_true(full.recalibrate_vhv(), "recalibrate_vhv");  // Recalibrate VHV, () → bool
    check_true(full.read_range_status(mm, err), "range_after_recalibration");

    check_true(full.start_continuous_range(50), "start_continuous_range"); // Start continuous ranging, (uint16_t period_ms) → bool
    bool continuousOk = true;
    for (int i = 0; i < 5; i++) {
        bool ok = full.read_range_continuous(mm) || full.last_error() != VL6180XMinimal::ERROR_TIMEOUT; // Wait for next sample, (uint8_t& mm) → bool
        continuousOk = continuousOk && ok;
    }
    check_true(continuousOk, "continuous_samples");
    full.stop_continuous_range();                           // Stop continuous ranging, () → void
    HAL_Delay(200);

    full.set_range_thresholds(30, 80);                      // Range thresholds, (uint8_t low, uint8_t high) → bool
    full.enable_interrupt(VL6180XFull::SOURCE_RANGE, VL6180XFull::MODE_OUT_OF_WINDOW); // Enable an interrupt, (uint8_t source, uint8_t mode) → bool
    full.start_continuous_range(50);
    HAL_Delay(300);
    check_true(full.poll_interrupt() <= 7, "poll_interrupt_mask"); // Read and clear, () → uint8_t
    full.stop_continuous_range();
    HAL_Delay(200);
    full.disable_interrupt(VL6180XFull::SOURCE_RANGE);      // Disable an interrupt, (uint8_t source) → bool
    full.enable_interrupt(VL6180XFull::SOURCE_RANGE, VL6180XFull::MODE_NEW_SAMPLE_READY);
    full.poll_interrupt();

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
