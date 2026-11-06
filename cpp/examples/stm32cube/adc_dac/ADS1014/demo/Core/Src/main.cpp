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

// Shunt-current monitor: a 0.1 ohm shunt in the load supply line is read on
// the ±0.256 V range; the traditional comparator watches for more than 1 A.

static const float SHUNT_OHMS = 0.1f;
static const int SAMPLES = 20;

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, ADS1014Minimal::I2C_ADDRESS);
    ADS1014Full adc(connection);                        // Create ADS1014 Full driver, (connection)

    // --- Pick the range and speed for a small shunt voltage ---
    // ±0.256 V gives 0.125 mV per code; 250 SPS keeps noise low and is plenty
    // for a once-per-second report.
    adc.setGain(ADS1014Full::PGA_0_256);                // Set PGA, (pga 0–5) → bool
    adc.setDataRate(ADS1014Full::DR_250);               // Set data rate, (dr 0–6) → bool

    // --- Arm the over-current comparator ---
    // 0.1 V across 0.1 ohm is 1 A. Traditional comparator with hysteresis
    // (clears again below 0.08 V), latched so a brief spike is not missed,
    // and 4 consecutive conversions are needed to assert.
    adc.setThresholds(0.08f, 0.1f);                     // Set both thresholds, (low V, high V) → bool
    adc.configureComparator(ADS1014Full::COMP_TRADITIONAL, ADS1014Full::ACTIVE_LOW, true, ADS1014Full::QUEUE_4);  // Configure comparator, (mode, polarity, latch, queue) → bool
    adc.enableInterrupt(ADS1014Full::SOURCE_COMPARATOR);  // Route comparator to ALERT/RDY, (source) → bool

    // --- Monitor the current ---
    // The pin level is not wired in this demo, so the alert state is inferred
    // from the measured current; clearAlert() releases the latch afterwards.
    for (int i = 0; i < SAMPLES; i++) {
        float v = adc.readVoltage();                    // Read voltage, () → V
        float amps = v / SHUNT_OHMS;
        printf("%.3f V -> %.2f A\r\n", (double)v, (double)amps);
        if (amps > 1.0f) {
            printf("over-current!\r\n");
            adc.clearAlert();                           // Release latched alert, () → void
        }
        HAL_Delay(1000);
    }

    // --- Release the alert pin when finished ---
    adc.disableInterrupt(ADS1014Full::SOURCE_COMPARATOR);  // Release ALERT/RDY, (source) → void
    while (true) HAL_Delay(1000);
}
