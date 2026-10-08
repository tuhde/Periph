#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "TMP117.h"

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

    I2CConnectionSTM32Cube connection(&hi2c1, TMP117Minimal::I2C_ADDRESS);

    TMP117Minimal sensor(connection);                       // Create TMP117 driver, (connection)
    TMP117Full full(connection);                            // Create TMP117 Full driver, (connection)
    full.configure(TMP117Full::Mode::Continuous, 8, 0.125f);  // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
    HAL_Delay(300);
    float t = sensor.readTemperature();                     // Read temperature, () → °C
    check_true(t >= -40.0f && t <= 125.0f, "temperature_plausible");

    full.configure(TMP117Full::Mode::Continuous, 32, 4.0f);  // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
    TMP117Full::Config cfg = full.getConfig();              // Read conversion config, () → Config
    check_true(cfg.mode == TMP117Full::Mode::Continuous && cfg.averaging == 32 && cfg.cycleSeconds == 4.0f,
               "config_roundtrip");
    full.configure(TMP117Full::Mode::Shutdown, 0, 0.0155f);  // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
    check_true(full.isShutdown(), "shutdown");
    full.triggerOneShot();                                  // Start one conversion, () → void
    HAL_Delay(50);
    check_true(full.isDataReady(), "one_shot_data_ready");
    check_true(full.isShutdown(), "one_shot_returns_to_shutdown");
    full.configure();                                       // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
    check_true(!full.isShutdown(), "continuous");

    full.setHighLimit(80.0f);                               // Set THIGH_LIMIT, (celsius °C) → void
    check_true(full.getHighLimit() == 80.0f, "high_limit_roundtrip");
    full.setLowLimit(-10.25f);                              // Set TLOW_LIMIT, (celsius °C) → void
    check_true(full.getLowLimit() == -10.25f, "low_limit_roundtrip");
    full.setTemperatureOffset(0.5f);                        // Set calibration offset, (celsius °C) → void
    check_true(full.getTemperatureOffset() == 0.5f, "offset_roundtrip");
    full.setTemperatureOffset(0.0f);                        // Set calibration offset, (celsius °C) → void

    check_true(!full.isEepromBusy(), "eeprom_not_busy");
    uint16_t eeprom2 = 0;
    full.readEepromScratch(2, eeprom2);                     // Read EEPROM scratch, (slot 1|2|3, value&) → bool
    full.writeEepromScratch(2, 0xA55A);                     // Write EEPROM scratch, (slot 2, value 16-bit) → bool
    uint16_t scratch = 0;
    check_true(full.readEepromScratch(2, scratch) && scratch == 0xA55A, "eeprom2_volatile_roundtrip");

    // One real EEPROM program cycle (the conformance-checked eeprom_write_ready
    // timing): rewrite EEPROM2's original value while unlocked, so the stored
    // power-on value is unchanged. Costs one EEPROM2 endurance cycle per run.
    full.unlockEeprom();                                    // Unlock EEPROM, () → void
    full.writeEepromScratch(2, eeprom2);                    // Write EEPROM scratch, (slot 2, value 16-bit) → bool
    int waitedMs = 0;
    while (full.isEepromBusy() && waitedMs < 50) {          // Check EEPROM busy, () → bool
        HAL_Delay(1);
        waitedMs += 1;
    }
    full.lockEeprom();                                      // Lock EEPROM, () → void
    check_true(waitedMs < 50, "eeprom_write_ready");
    check_true(full.readEepromScratch(2, scratch) && scratch == eeprom2, "eeprom2_restored");

    // High limit below ambient forces HIGH_Alert on the next conversion; in
    // Alert mode the flag latches until CONFIGURATION is read.
    full.configureAlert();                                  // Configure ALERT, (mode=Alert, polarity=ActiveLow, pinFunction=Alert) → void
    full.configure(TMP117Full::Mode::Continuous, 0, 0.0155f);  // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
    full.setHighLimit(t - 20.0f);                           // Set THIGH_LIMIT, (celsius °C) → void
    HAL_Delay(100);
    check_true((full.pollInterrupt() & TMP117Full::SOURCE_HIGH) != 0, "poll_interrupt_high");
    full.setHighLimit(80.0f);                               // Set THIGH_LIMIT, (celsius °C) → void
    HAL_Delay(100);
    full.pollInterrupt();                                   // Read alert flags, () → uint8_t mask
    check_true((full.pollInterrupt() & TMP117Full::SOURCE_HIGH) == 0, "poll_interrupt_clear");

    // Soft reset reloads CONFIGURATION, the limits and the offset from EEPROM.
    full.reset();                                           // Software reset, () → void
    check_true(full.getConfig().mode == TMP117Full::Mode::Continuous, "reset_restores_config");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
