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

static void onAlert(uint8_t status) { (void)status; }

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, ADS1014Minimal::I2C_ADDRESS);
    ADS1014Full adc(connection);                        // Create ADS1014 Full driver, (connection)
                                                        // no I2C traffic; defaults ±2.048 V, 1600 SPS

    int16_t raw = adc.readRaw();                        // Read raw code, () → int16 (−2048…2047)
                                                        // single-shot AIN0 − AIN1, polls OS until idle
    float v = adc.readVoltage();                        // Read voltage, () → V
                                                        // raw × full-scale / 2048
    printf("raw %d voltage %.4f V\r\n", (int)raw, (double)v);

    adc.setGain(ADS1014Full::PGA_1_024);                // Set PGA, (pga 0–5) → bool
                                                        // ±1.024 V range, 0.5 mV per code
    printf("gain %u\r\n", (unsigned)adc.getGain());     // Read PGA code, () → uint8
                                                        // cached PGA[2:0]
    printf("full scale %.3f V\r\n", (double)adc.fullScale());  // Read full-scale range, () → V
                                                        // ±FSR of the current gain
    printf("lsb %.6f V\r\n", (double)adc.lsb());        // Read code size, () → V
                                                        // fullScale() / 2048

    adc.setDataRate(ADS1014Full::DR_250);               // Set data rate, (dr 0–6) → bool
                                                        // DR_250 = 250 SPS
    printf("dr code %u\r\n", (unsigned)adc.getDataRate());  // Read data-rate code, () → uint8
                                                        // cached DR[2:0]
    printf("sps %u\r\n", (unsigned)adc.dataRateSps());  // Read data rate, () → SPS
                                                        // table lookup of the DR code

    adc.startContinuous();                              // Start continuous conversion, () → void
                                                        // MODE = 0 at the current data rate
    HAL_Delay(20);
    printf("continuous raw %d\r\n", (int)adc.readContinuousRaw());  // Read latest code, () → int16
                                                        // reads CONVERSION without touching Config
    printf("continuous %.4f V\r\n", (double)adc.readContinuous());  // Read latest voltage, () → V
                                                        // raw × lsb()
    adc.stopContinuous();                               // Stop continuous conversion, () → void
                                                        // MODE = 1, back to single-shot
    printf("converting %d\r\n", adc.isConverting());    // Check conversion in progress, () → bool
                                                        // CONFIG.OS = 0 (single-shot only)
    printf("config 0x%04X\r\n", (unsigned)adc.readConfig());  // Read CONFIG register, () → uint16
                                                        // raw 16-bit register

    adc.setThresholds(-0.2f, 0.5f);                     // Set both thresholds, (low V, high V) → bool
                                                        // converted with lsb(), clamped to the code range
    adc.setLowThresholdRaw(-200);                       // Set low threshold, (raw −2048…2047) → bool
                                                        // LO_THRESH = raw << 4
    adc.setHighThresholdRaw(500);                       // Set high threshold, (raw −2048…2047) → bool
                                                        // HI_THRESH = raw << 4
    int16_t lo = 0, hi = 0;
    adc.getThresholdsRaw(&lo, &hi);                     // Read thresholds, (low*, high*) → void
                                                        // sign-extended 12-bit codes
    printf("thresholds %d %d\r\n", (int)lo, (int)hi);

    adc.configureComparator(ADS1014Full::COMP_WINDOW, ADS1014Full::ACTIVE_LOW, true, ADS1014Full::QUEUE_2);  // Configure comparator, (mode, polarity, latch, queue) → bool
                                                        // window mode, latching, 2 consecutive conversions
    adc.clearAlert();                                   // Release latched alert, () → void
                                                        // reads CONVERSION
    adc.disableComparator();                            // Disable comparator, () → void
                                                        // COMP_QUE = 11, ALERT/RDY high-impedance

    adc.enableInterrupt(ADS1014Full::SOURCE_CONVERSION_READY);  // Route conversion-ready to ALERT/RDY, (source) → bool
                                                        // HI_THRESH = 0x8000, LO_THRESH = 0, COMP_QUE = 00
    printf("status %u\r\n", (unsigned)adc.pollInterrupt());  // Poll ALERT/RDY, () → uint8 source
                                                        // INT pin level, else CONFIG.OS fallback
    adc.disableInterrupt(ADS1014Full::SOURCE_CONVERSION_READY);  // Release ALERT/RDY, (source) → void
                                                        // COMP_QUE = 11

    adc.enableInterrupt(ADS1014Full::SOURCE_COMPARATOR);  // Route comparator to ALERT/RDY, (source) → bool
                                                        // restores the thresholds set above
    adc.onInterrupt(onAlert);                           // Subscribe to ALERT/RDY, (callback, intPin=nullptr) → void
                                                        // pin edge via connection.intPin(); no-op without a pin
    HAL_Delay(1000);
    adc.offInterrupt();                                 // Unsubscribe, () → void
                                                        // detaches the edge handler
    adc.reset();                                        // Reset to power-up defaults, () → void
                                                        // rewrites CONFIG and both thresholds
    while (true) HAL_Delay(1000);
}
