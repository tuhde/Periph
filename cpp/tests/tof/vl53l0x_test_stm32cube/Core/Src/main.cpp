#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "VL53L0X.h"

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

    I2CConnectionSTM32Cube connection(&hi2c1, VL53L0XMinimal::I2C_ADDRESS);

    VL53L0XMinimal sensor(connection);                      // Create VL53L0X driver, (connection)
    uint16_t d = sensor.distance();                         // Measure distance, () → uint16_t mm
    check_true(d <= 8191, "distance_in_range");
    sensor.rangeValid();                                    // Check last measurement, () → bool

    VL53L0XFull full(connection);                           // Create VL53L0X Full driver, (connection)
    check_true(full.modelId() == 0xEE, "model_id");         // Read model ID, () → uint8_t
    check_true(full.revisionId() > 0, "revision_id");       // Read revision ID, () → uint8_t
    uint32_t budget = full.timingBudget();                  // Read timing budget, () → uint32_t µs
    check_true(budget >= 20000 && budget <= 40000, "default_budget");

    full.distance();                                        // Measure distance, () → uint16_t mm
    VL53L0XFull::Measurement m = full.readMeasurement();    // Read result block, () → Measurement
    check_true(m.rangeStatus <= 15 && m.signalRateMcps >= 0.0f, "measurement_record");

    full.setTimingBudget(50000);                            // Set timing budget, (budgetUs µs) → bool
    budget = full.timingBudget();                           // Read timing budget, () → uint32_t µs
    check_true(budget > 49700 && budget < 50300, "budget_roundtrip");
    full.setSignalRateLimit(0.1f);                          // Set signal-rate limit, (limitMcps MCPS) → bool
    float limit = full.signalRateLimit();                   // Read signal-rate limit, () → float MCPS
    check_true(limit > 0.09f && limit < 0.11f, "signal_rate_roundtrip");
    full.setVcselPulsePeriod(VL53L0XFull::VcselPeriodType::PreRange, 18);    // Set VCSEL period, (type, pclks) → bool
    full.setVcselPulsePeriod(VL53L0XFull::VcselPeriodType::FinalRange, 14);  // Set VCSEL period, (type, pclks) → bool
    check_true(full.vcselPulsePeriod(VL53L0XFull::VcselPeriodType::PreRange) == 18 &&
               full.vcselPulsePeriod(VL53L0XFull::VcselPeriodType::FinalRange) == 14, "vcsel_roundtrip");
    full.setProfile(VL53L0XFull::Profile::Default);         // Apply ranging profile, (profile) → bool
    budget = full.timingBudget();                           // Read timing budget, () → uint32_t µs
    check_true(full.vcselPulsePeriod(VL53L0XFull::VcselPeriodType::PreRange) == 14 &&
               budget > 32700 && budget < 33300, "profile_default");

    float original = full.offset();                         // Read range offset, () → float mm
    full.setOffset(-10.25f);                                // Set range offset, (offsetMm mm) → bool
    check_true(full.offset() == -10.25f, "offset_roundtrip");
    full.setOffset(original);                               // Set range offset, (offsetMm mm) → bool

    full.setInterruptThresholds(100, 800);                  // Set distance thresholds, (lowMm mm, highMm mm) → bool
    uint16_t lo = 0, hi = 0;
    full.interruptThresholds(lo, hi);                       // Read distance thresholds, (lowMm&, highMm&) → void
    check_true(lo == 100 && hi == 800, "thresholds_roundtrip");

    // Back-to-back continuous ranging, then timed mode.
    full.startContinuous();                                 // Start continuous ranging, (periodMs=0 ms) → void
    bool contOk = true;
    for (int i = 0; i < 3; i++) contOk = full.readContinuous() <= 8191 && contOk;  // Read next continuous result, () → uint16_t mm
    check_true(contOk, "continuous_readings");
    full.stopContinuous();                                  // Stop continuous ranging, () → void
    HAL_Delay(50);
    full.pollInterrupt();                                   // Read and clear status, () → uint8_t
    full.startContinuous(100);                              // Start continuous ranging, (periodMs=0 ms) → void
    bool timedOk = true;
    for (int i = 0; i < 2; i++) timedOk = full.readContinuous() <= 8191 && timedOk;  // Read next continuous result, () → uint16_t mm
    check_true(timedOk, "timed_readings");
    full.stopContinuous();                                  // Stop continuous ranging, () → void
    HAL_Delay(150);
    full.pollInterrupt();                                   // Read and clear status, () → uint8_t

    full.startContinuous();                                 // Start continuous ranging, (periodMs=0 ms) → void
    HAL_Delay(100);
    check_true(full.pollInterrupt() == VL53L0XFull::SOURCE_NEW_SAMPLE_READY, "poll_interrupt_new_sample");
    full.stopContinuous();                                  // Stop continuous ranging, () → void
    HAL_Delay(50);
    full.pollInterrupt();                                   // Read and clear status, () → uint8_t

    check_true(full.recalibrate(), "recalibrate");          // Rerun reference calibration, () → bool
    check_true(full.distance() <= 8191, "recalibrate_then_distance");  // Measure distance, () → uint16_t mm

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
