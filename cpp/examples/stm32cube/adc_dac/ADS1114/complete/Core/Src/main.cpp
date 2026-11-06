#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "ADS1114.h"

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

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, ADS1114Minimal::I2C_ADDRESS);
    ADS1114Full adc(connection);                            // Create ADS1114 Full driver, (connection)
                                                            // no I²C traffic; Config word is rebuilt per conversion
    int16_t raw = adc.readRaw();                            // Read raw code, () → int16_t
                                                            // single-shot conversion, signed 16-bit
    printf("raw %d\r\n", (int)raw);
    float v = adc.readVoltage();                            // Read voltage, () → float V
                                                            // raw × lsb()
    printf("voltage %.5f V\r\n", (double)v);
    adc.setGain(ADS1114Full::PGA_4_096);                    // Set gain, (pga 0–5) → void
                                                            // ±4.096 V full scale
    printf("gain %d\r\n", (int)adc.getGain());
    float fs = adc.fullScale();                             // Full scale, () → float V
                                                            // ±FSR of the current gain
    float lsb = adc.lsb();                                  // LSB, () → float V
                                                            // fullScale() / 32768
    printf("fsr %.3f lsb %.7f\r\n", (double)fs, (double)lsb);
    adc.setDataRate(ADS1114Full::DR_250);                   // Set data rate, (dr 0–7) → void
                                                            // DR field of the Config word
    uint16_t sps = adc.dataRateSps();                       // Data rate, () → uint16_t SPS
                                                            // samples per second of the current setting
    printf("rate %u SPS\r\n", (unsigned)sps);
    adc.startContinuous();                                  // Start continuous, () → void
                                                            // MODE ← 0
    HAL_Delay(50);
    int16_t craw = adc.readContinuousRaw();                 // Read continuous raw, () → int16_t
                                                            // CONVERSION without touching Config
    float cv = adc.readContinuous();                        // Read continuous, () → float V
                                                            // readContinuousRaw() × lsb()
    printf("continuous %d %.5f V\r\n", (int)craw, (double)cv);
    adc.stopContinuous();                                   // Stop continuous, () → void
                                                            // MODE ← 1
    bool busy = adc.isConverting();                         // Check conversion, () → bool
                                                            // CONFIG.OS == 0
    uint16_t cfg = adc.readConfig();                        // Read CONFIG, () → uint16_t
                                                            // raw 16-bit register
    printf("converting %d config 0x%04X\r\n", (int)busy, (unsigned)cfg);
    adc.setThresholds(0.5f, 3.0f);                          // Set thresholds, (low V, high V) → void
                                                            // converted with the current lsb(), clamped
    adc.setLowThresholdRaw(4000);                           // Set low threshold, (raw) → void
                                                            // LO_THRESH ← raw & 0xFFFF
    adc.setHighThresholdRaw(20000);                         // Set high threshold, (raw) → void
                                                            // HI_THRESH ← raw & 0xFFFF
    int16_t lo, hi;
    adc.getThresholdsRaw(&lo, &hi);                         // Get thresholds, (int16_t* low, int16_t* high) → void
                                                            // read back, sign-extended
    printf("thresholds %d %d\r\n", (int)lo, (int)hi);
    adc.configureComparator(ADS1114Full::COMP_WINDOW, ADS1114Full::ACTIVE_LOW, true, ADS1114Full::QUEUE_2);// Configure comparator, (mode, polarity, latch=false, queue=QUEUE_1) → void
                                                            // window mode, latching, 2-sample queue
    adc.clearAlert();                                       // Clear alert, () → void
                                                            // reads CONVERSION to release the latch
    adc.disableComparator();                                // Disable comparator, () → void
                                                            // COMP_QUE ← 11
    adc.enableInterrupt(ADS1114Full::SOURCE_CONVERSION_READY);// Enable interrupt, (source) → void
                                                            // ALERT/RDY signals conversion-ready
    uint8_t status = adc.pollInterrupt();                   // Poll interrupt, () → uint8_t source
                                                            // enabled source if ALERT/RDY is asserted
    printf("poll %u\r\n", (unsigned)status);
    adc.disableInterrupt(ADS1114Full::SOURCE_CONVERSION_READY);// Disable interrupt, (source) → void
                                                            // releases ALERT/RDY
    adc.reset();                                            // Reset, () → void
                                                            // writes the reset values back
    while (true) HAL_Delay(1000);
}
