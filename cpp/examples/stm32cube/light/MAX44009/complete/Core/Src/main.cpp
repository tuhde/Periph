#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "MAX44009.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, MAX44009Minimal::I2C_ADDRESS);
    MAX44009Full sensor(connection);                    // Create MAX44009 Full driver, (connection)
    float lx = sensor.lux();                            // Read illuminance, () → float lux
    // 2^E × M × 0.045; 188006.4 on overrange
    printf("lux %.2f\n", (double)lx);
    uint8_t e = 0, m = 0;
    bool over = sensor.readRaw(e, m);                   // Read exponent and mantissa, (exponent&, mantissa&) → bool overrange
    // one consistent sample: LUX_HIGH, LUX_LOW, LUX_HIGH
    printf("E=%d M=%d overrange=%d\n", (int)e, (int)m, (int)over);
    bool last = sensor.isOverrange();                   // Query the last reading, () → bool
    // true if the last exponent field was 15
    float res = sensor.resolution();                    // Lux per LSB, () → float lux
    // 2^E × 0.045 for the last reading
    printf("overrange %d resolution %.3f\n", (int)last, (double)res);
    sensor.setContinuous(true);                         // Select measurement mode, (continuous bool) → void
    // true: a new measurement as soon as one finishes
    sensor.setManual(100.0f, false);                    // Select manual range, (integrationMs, divideBy8=false) → void
    // nearest of 800/400/200/100/50/25/12.5/6.25 ms
    MAX44009Full::Config cfg = sensor.getConfig();      // Read CONFIG, () → Config
    // {continuous, manual, divideBy8, integrationMs}
    printf("manual %d integration %.2f ms\n", (int)cfg.manual, (double)cfg.integrationMs);
    sensor.setAutomatic();                              // Select automatic range, () → void
    // the chip chooses the current divider and integration time
    sensor.setContinuous(false);                        // Back to one measurement per 800 ms, (continuous bool) → void
    bool ok = sensor.setThresholds(50.0f, 500.0f);      // Set the interrupt window, (lowLux, highLux) → bool
    // 4 mantissa MSBs: about 1/16 to 1/8 of the value
    float lo = 0, hi = 0;
    sensor.getThresholds(lo, hi);                       // Read the window, (lowLux&, highLux&) → void
    printf("window %.2f .. %.2f lx (set %d)\n", (double)lo, (double)hi, (int)ok);
    sensor.setThresholdDelay(2.0f);                     // Set the threshold delay, (seconds) → void
    // 0–25.5 s in 0.1 s steps
    float delay = sensor.getThresholdDelay();           // Read the delay, () → float s
    printf("delay %.1f s\n", (double)delay);
    sensor.enableInterrupt();                           // Enable the interrupt, () → void
    // INT_ENABLE = 1
    sensor.onInterrupt([](uint8_t status) { (void)status; });  // Subscribe, (callback(status), intPin=nullptr) → void
    // falling edge on INT; call service() periodically without a pin
    sensor.service();                                   // Poll once and dispatch the callback, () → void
    uint8_t status = sensor.pollInterrupt();            // Read and clear INT_STATUS, () → uint8_t
    // bit 0 = SOURCE_THRESHOLD
    printf("status %d\n", (int)status);
    sensor.offInterrupt();                              // Unsubscribe, () → void
    sensor.disableInterrupt();                          // Disable the interrupt, () → void
    // INT_ENABLE = 0; also clears a pending status
    while (1) {}
}
