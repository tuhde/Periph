#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Max17048.h"

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

static volatile uint16_t g_alert = 0;
static void on_alert(uint16_t status) { g_alert = status; }

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x36);
    Max17048Full gauge(connection, 1);                      // Create MAX17048 driver, (connection, cells)

    printf("%.3f V\r\n", (double)(gauge.voltage()));                                                // Cell voltage, () → float V
    printf("%.2f %%\r\n", (double)(gauge.percent()));                                               // State of charge, () → float %
    printf("raw VCELL 0x%04X\r\n", (unsigned)(gauge.raw_voltage()));                                // VCELL register, () → uint16_t
    printf("raw SOC 0x%04X\r\n", (unsigned)(gauge.raw_soc()));                                      // SOC register, () → uint16_t
    printf("%.2f %%/h\r\n", (double)(gauge.charge_rate()));                                         // Charge/discharge rate, () → float %/h
    printf("version 0x%04X\r\n", (unsigned)(gauge.version()));                                      // VERSION register, () → uint16_t
    printf("id 0x%02X\r\n", (unsigned)(gauge.chip_id()));                                           // Factory ID, () → uint8_t
    gauge.set_rcomp(0x97);                                  // RCOMP, (value)
    printf("rcomp 0x%02X\r\n", (unsigned)(gauge.get_rcomp()));                                      // () → uint8_t
    gauge.compensate_temperature(25.0f);                    // RCOMP from the temperature, (celsius)
    gauge.set_alert_soc(10);                                // Empty alert threshold, (percent) 1-32
    printf("alert soc %d %%\r\n", (int)(gauge.get_alert_soc()));                                    // () → uint8_t %
    gauge.set_soc_change_alert(true);                       // Alert on a 1 % SOC change, (enabled)
    gauge.set_voltage_alert(3.3f, 4.25f);                   // Voltage window, (min_v, max_v) → bool; 0 / 5.1 V disable a limit
    float min_v = 0, max_v = 0;
    gauge.get_voltage_alert(&min_v, &max_v);                // () → min_v, max_v in V
    printf("window %.2f .. %.2f V\r\n", (double)(min_v), (double)(max_v));
    gauge.set_reset_voltage(3.0f);                          // Reset voltage, (volts)
    printf("reset %.2f V\r\n", (double)(gauge.get_reset_voltage()));                                // () → float V
    gauge.enable_voltage_reset_alert(true);                 // Voltage reset asserts ALRT, (enabled)
    gauge.set_comparator_disabled(false);                   // Hibernate comparator off, (disabled)
    gauge.set_hibernate_thresholds(26.624f, 0.06f);         // Hibernate thresholds, (hib_thr_pct_per_h, act_thr_v)
    float hib = 0, act = 0;
    gauge.get_hibernate_thresholds(&hib, &act);             // () → hib_thr_pct_per_h, act_thr_v
    printf("hibernate %.2f %%/h, %.3f V\r\n", (double)(hib), (double)(act));
    gauge.disable_hibernate();                              // Never hibernate, ()
    gauge.force_hibernate();                                // Always hibernate, ()
    gauge.set_hibernate_thresholds(26.624f, 0.06f);         // Restore the defaults
    printf("hibernating %d\r\n", (int)gauge.is_hibernating());                                      // Hibernate status, () → bool
    printf("status 0x%04X\r\n", (unsigned)(gauge.read_status()));                                   // STATUS register, () → uint16_t
    gauge.clear_status(Max17048Full::SOURCE_RESET);         // Clear flags and ALRT, (mask)
    printf("config 0x%04X\r\n", (unsigned)(gauge.read_config()));                                   // CONFIG register, () → uint16_t
    gauge.write_config(gauge.read_config());                // Write CONFIG, (value)
    printf("alert sources 0x%04X\r\n", (unsigned)(gauge.pollInterrupt()));                          // Alert sources, () → uint16_t
    gauge.enableInterrupt(Max17048Full::SOURCE_SOC_CHANGE); // Enable a source, (source)
    gauge.disableInterrupt(Max17048Full::SOURCE_SOC_CHANGE);// Disable a source, (source)
    gauge.onInterrupt(on_alert);                            // Subscribe to ALRT, (callback, intPin = nullptr)
    gauge.offInterrupt();                                   // Unsubscribe, ()
    gauge.enable_sleep(true);                               // Allow sleep mode, (enabled)
    gauge.sleep();                                          // Sleep, ()
    gauge.wake();                                           // Wake up, ()
    gauge.enable_sleep(false);
    gauge.quick_start();                                    // Restart the SOC estimate, (); only if the first SOC is clearly wrong
    gauge.reset();                                          // Power-on reset, (); waits 1 s
    while (true) HAL_Delay(1000);
}
