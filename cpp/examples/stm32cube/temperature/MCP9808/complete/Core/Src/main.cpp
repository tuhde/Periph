#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "MCP9808.h"

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

static volatile uint8_t pendingStatus = 0;
static volatile bool pending = false;

static void onAlert(uint8_t status) {
    pendingStatus = status;
    pending = true;
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, MCP9808Minimal::I2C_ADDRESS);
    MCP9808Full sensor(connection);                         // Create MCP9808 Full driver, (connection)
                                                            // checks MANUFACTURER_ID 0x0054 and DEVICE_ID 0x04

    float t = sensor.readTemperature();                     // Read ambient temperature, () → °C
                                                            // masks TA's 3 status bits, decodes 1/16 °C two's complement
    printf("temperature %.4f C\r\n", (double)t);

    sensor.setResolution(0.25f);                            // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → bool
                                                            // 0.25 °C step converts in ~65 ms instead of 250 ms
    printf("resolution %.4f C\r\n", (double)sensor.getResolution());  // Read resolution, () → °C
                                                            // decodes the RESOLUTION register code

    sensor.shutdown();                                      // Enter Shutdown mode, () → void
                                                            // stops conversion; TA keeps its last value
    printf("shutdown %d\r\n", sensor.isShutdown());              // Check Shutdown mode, () → bool
                                                            // reads CONFIG.SHDN
    sensor.wake();                                          // Leave Shutdown mode, () → void
                                                            // resumes continuous conversion
    HAL_Delay(100);

    sensor.setUpperLimit(30.0f);                            // Set TUPPER, (celsius °C) → void
                                                            // rounded to the nearest 0.25 °C step
    sensor.setLowerLimit(10.0f);                            // Set TLOWER, (celsius °C) → void
                                                            // rounded to the nearest 0.25 °C step
    sensor.setCriticalLimit(45.0f);                         // Set TCRIT, (celsius °C) → void
                                                            // rounded to the nearest 0.25 °C step
    printf("upper %.2f C\r\n", (double)sensor.getUpperLimit());  // Read TUPPER, () → °C
                                                            // decodes the 0.25 °C two's-complement boundary
    printf("lower %.2f C\r\n", (double)sensor.getLowerLimit());  // Read TLOWER, () → °C
                                                            // decodes the 0.25 °C two's-complement boundary
    printf("critical %.2f C\r\n", (double)sensor.getCriticalLimit());  // Read TCRIT, () → °C
                                                            // decodes the 0.25 °C two's-complement boundary

    sensor.setHysteresis(1.5f);                             // Set hysteresis, (celsius 0|1.5|3.0|6.0) → bool
                                                            // applied on the cooling edge of each boundary only
    printf("hysteresis %.1f C\r\n", (double)sensor.getHysteresis());  // Read hysteresis, () → °C
                                                            // decodes CONFIG.THYST

    // sensor.lockCriticalLimit();                          // Lock TCRIT, () → void
    //                                                      // irreversible until power-on reset
    // sensor.lockWindowLimits();                           // Lock TUPPER/TLOWER, () → void
    //                                                      // irreversible until power-on reset
    printf("crit locked %d\r\n", sensor.isCriticalLimitLocked());  // Check TCRIT lock, () → bool
                                                            // reads CONFIG.CRIT_LOCK
    printf("win locked %d\r\n", sensor.isWindowLimitsLocked());  // Check TUPPER/TLOWER lock, () → bool
                                                            // reads CONFIG.WIN_LOCK

    sensor.configureAlert(MCP9808Full::AlertMode::All,
                          MCP9808Full::AlertOutput::Interrupt,
                          MCP9808Full::AlertPolarity::ActiveLow);  // Configure Alert, (mode=All, output=Comparator, polarity=ActiveLow) → bool
                                                            // sets ALERT_SEL, ALERT_MOD and ALERT_POL together
    sensor.enableAlert();                                   // Enable Alert output, () → void
                                                            // sets CONFIG.ALERT_CNT
    printf("alert asserted %d\r\n", sensor.isAlertAsserted());  // Check Alert output, () → bool
                                                            // reads the read-only CONFIG.ALERT_STAT

    uint8_t status = sensor.pollInterrupt();                // Read boundary status, () → uint8_t mask
                                                            // TA's live bits: SOURCE_LOWER/UPPER/CRITICAL, nothing cleared
    printf("below lower %d above upper %d critical %d\r\n",
        (status & MCP9808Full::SOURCE_LOWER) != 0,
        (status & MCP9808Full::SOURCE_UPPER) != 0,
        (status & MCP9808Full::SOURCE_CRITICAL) != 0);
    sensor.clearInterrupt();                                // Clear interrupt-mode Alert, () → void
                                                            // writes CONFIG.INT_CLEAR=1; no effect in comparator mode

    sensor.onInterrupt(onAlert);                            // Subscribe to Alert, (callback, intPin=nullptr) → void
                                                            // callback receives the pollInterrupt() mask; needs an InputPin
    HAL_Delay(5000);
    if (pending) printf("alert, status mask %u\r\n", (unsigned)pendingStatus);
    sensor.offInterrupt();                                  // Unsubscribe, () → void
                                                            // detaches the edge handler
    sensor.disableAlert();                                  // Disable Alert output, () → void
                                                            // clears CONFIG.ALERT_CNT
    while (true) HAL_Delay(1000);
}
