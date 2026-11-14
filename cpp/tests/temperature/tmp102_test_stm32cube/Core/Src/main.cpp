#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "TMP102.h"

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

    I2CConnectionSTM32Cube connection(&hi2c1, TMP102Minimal::I2C_ADDRESS);

    TMP102Minimal sensor(connection);                       // Create TMP102 driver, (connection)
    TMP102Full full(connection);                            // Create TMP102 Full driver, (connection)
    HAL_Delay(50);
    float t = sensor.readTemperature();                     // Read temperature, () → °C
    check_true(t >= -40.0f && t <= 125.0f, "temperature_plausible");
    float rawT = (float)full.readRaw() * 0.0625f;           // Read raw count, () → int16_t
    check_true(rawT - t < 1.0f && t - rawT < 1.0f, "raw_matches_temperature");
    check_true((full.readConfig() & 0x6000) == 0x6000, "default_config");  // Read CONFIGURATION, () → uint16_t

    full.setConversionRate(1.0f);                           // Set conversion rate, (hz) → bool
    check_true(((full.readConfig() >> 6) & 3) == 1, "rate_roundtrip");
    full.setConversionRate(4.0f);                           // Set conversion rate, (hz) → bool

    full.setTHigh(40.0f);                                   // Set T_HIGH, (celsius °C) → void
    check_true(full.getTHigh() == 40.0f, "t_high_roundtrip");
    full.setTLow(-10.25f);                                  // Set T_LOW, (celsius °C) → void
    check_true(full.getTLow() == -10.25f, "t_low_roundtrip");
    full.setTHigh(80.0f);                                   // Set T_HIGH, (celsius °C) → void
    full.setTLow(75.0f);                                    // Set T_LOW, (celsius °C) → void

    full.setFaultQueue(4);                                  // Set fault queue, (count) → bool
    check_true(((full.readConfig() >> 11) & 3) == 2, "fault_queue");
    full.setFaultQueue(1);                                  // Set fault queue, (count) → bool
    full.setPolarity(true);                                 // Set ALERT polarity, (activeHigh) → void
    check_true((full.readConfig() & 0x0400) != 0, "polarity");
    full.setPolarity(false);                                // Set ALERT polarity, (activeHigh) → void
    full.setThermostatMode(true);                           // Set thermostat mode, (interrupt) → void
    check_true((full.readConfig() & 0x0200) != 0, "thermostat_mode");
    full.setThermostatMode(false);                          // Set thermostat mode, (interrupt) → void

    full.setExtendedMode(true);                             // Set extended mode, (on) → void
    HAL_Delay(300);
    float ext = full.readTemperature();
    check_true(ext - t < 3.0f && t - ext < 3.0f, "extended_mode_reading");
    full.setExtendedMode(false);                            // Set extended mode, (on) → void

    full.shutdown();                                        // Shut down, () → void
    check_true((full.readConfig() & 0x0100) != 0, "shutdown");
    float t1 = 0.0f;
    bool ok = full.oneShot(t1);                             // One-shot reading, (celsius&) → bool
    check_true(ok && t1 >= -40.0f && t1 <= 125.0f, "one_shot_plausible");
    check_true((full.readConfig() & 0x0100) != 0, "one_shot_stays_shutdown");
    full.wake();                                            // Wake, () → void
    check_true((full.readConfig() & 0x0100) == 0, "wake");

    // T_HIGH below ambient forces an alert after the fault queue elapses.
    full.setThermostatMode(false);                          // Set thermostat mode, (interrupt) → void
    full.setTLow(t - 21.0f);                                // Set T_LOW, (celsius °C) → void
    full.setTHigh(t - 20.0f);                               // Set T_HIGH, (celsius °C) → void
    HAL_Delay(500);
    check_true(full.alertState(), "alert_state_active");    // Read alert state, () → bool
    check_true(full.pollInterrupt(), "poll_interrupt_active");  // Poll the alert state, () → bool
    full.setTHigh(80.0f);                                   // Set T_HIGH, (celsius °C) → void
    full.setTLow(75.0f);                                    // Set T_LOW, (celsius °C) → void
    HAL_Delay(500);
    check_true(!full.alertState(), "alert_state_clear");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
