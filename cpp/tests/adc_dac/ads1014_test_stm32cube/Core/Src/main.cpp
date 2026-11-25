#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "ADS1014.h"

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

    I2CConnectionSTM32Cube connection(&hi2c1, ADS1014Minimal::I2C_ADDRESS);

    ADS1014Minimal adc(connection);                     // Create ADS1014 driver, (connection)
    int16_t raw = adc.readRaw();                        // Read raw code, () → int16 (−2048…2047)
    check_true(raw >= -2048 && raw <= 2047, "raw_in_range");
    float v = adc.readVoltage();                        // Read voltage, () → V
    check_true(v > -7.0f && v < 7.0f, "voltage_plausible");

    ADS1014Full full(connection);                       // Create ADS1014 Full driver, (connection)
    full.reset();                                       // Reset to power-up defaults, () → void
    check_true((full.readConfig() & 0x7FFF) == 0x0583, "reset_config");  // Read CONFIG register, () → uint16

    full.setGain(ADS1014Full::PGA_0_256);               // Set PGA, (pga 0–5) → bool
    check_true(full.getGain() == ADS1014Full::PGA_0_256 && full.fullScale() == 0.256f, "gain_roundtrip");
    full.readRaw();                                     // Read raw code, () → int16
    check_true(((full.readConfig() >> 9) & 0x7) == ADS1014Full::PGA_0_256, "config_gain_bits");
    full.setGain(ADS1014Full::PGA_2_048);               // Set PGA, (pga 0–5) → bool

    full.setDataRate(ADS1014Full::DR_250);              // Set data rate, (dr 0–6) → bool
    check_true(full.getDataRate() == ADS1014Full::DR_250 && full.dataRateSps() == 250, "dr_roundtrip");
    full.readRaw();                                     // Read raw code, () → int16
    check_true(((full.readConfig() >> 5) & 0x7) == ADS1014Full::DR_250, "config_dr_bits");
    full.setDataRate(ADS1014Full::DR_1600);             // Set data rate, (dr 0–6) → bool

    full.startContinuous();                             // Start continuous conversion, () → void
    HAL_Delay(50);
    int16_t cont = full.readContinuousRaw();            // Read latest code, () → int16
    check_true(cont >= -2048 && cont <= 2047, "continuous_raw_in_range");
    check_true((full.readConfig() & 0x0100) == 0, "continuous_mode_bit");
    full.stopContinuous();                              // Stop continuous conversion, () → void
    check_true((full.readConfig() & 0x0100) != 0, "stop_mode_bit");

    full.setLowThresholdRaw(-100);                      // Set low threshold, (raw −2048…2047) → bool
    full.setHighThresholdRaw(300);                      // Set high threshold, (raw −2048…2047) → bool
    int16_t lo = 0, hi = 0;
    full.getThresholdsRaw(&lo, &hi);                    // Read thresholds, (low*, high*) → void
    check_true(lo == -100 && hi == 300, "threshold_roundtrip");
    full.setThresholds(-0.2f, 0.5f);                    // Set thresholds, (low V, high V) → bool
    full.getThresholdsRaw(&lo, &hi);                    // Read thresholds, (low*, high*) → void
    check_true(lo == -200 && hi == 500, "threshold_volts_roundtrip");

    full.configureComparator(ADS1014Full::COMP_WINDOW, ADS1014Full::ACTIVE_HIGH, true, ADS1014Full::QUEUE_2);  // Configure comparator, (mode, polarity, latch, queue) → bool
    check_true((full.readConfig() & 0x1F) == (0x10 | 0x08 | 0x04 | 0x01), "comparator_bits");
    full.clearAlert();                                  // Release latched alert, () → void
    full.disableComparator();                           // Disable comparator, () → void
    check_true((full.readConfig() & 0x3) == 3, "comparator_disabled");

    full.enableInterrupt(ADS1014Full::SOURCE_CONVERSION_READY);  // Route conversion-ready to ALERT/RDY, (source) → bool
    full.getThresholdsRaw(&lo, &hi);                    // Read thresholds, (low*, high*) → void
    check_true(lo == 0 && hi == -2048, "ready_thresholds");
    check_true((full.readConfig() & 0x3) == 0, "ready_que_00");
    full.disableInterrupt(ADS1014Full::SOURCE_CONVERSION_READY);  // Release ALERT/RDY, (source) → void
    full.reset();                                       // Reset to power-up defaults, () → void
    check_true((full.readConfig() & 0x7FFF) == 0x0583, "final_reset");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
