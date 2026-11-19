#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "HS30xx.h"

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

// Replace with code that cuts and restores the sensor supply (MOSFET or GPIO).
static void power_cycle() { HAL_Delay(100); }

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x44);
    HS30xxFull sensor(connection);                                  // Create HS30xx driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0;
    uint8_t status = 0, hb = 0, tb = 0;
    uint32_t id = 0;
    uint16_t reg = 0;
    bool ok = false;
    sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                    // request, 40 ms wait, 4-byte fetch; one retry on stale data
    printf("%.2f C, %.1f %%RH\r\n", t, rh);
    t = sensor.read_temperature();                                  // Read temperature only, () → float °C
                                                                    // NAN on failure
    printf("temperature %.2f C\r\n", t);
    rh = sensor.read_humidity();                                    // Read humidity only, () → float %RH
                                                                    // 2-byte fetch, NAN on failure
    printf("humidity %.1f %%RH\r\n", rh);
    sensor.read_raw(rh_raw, t_raw, status);                         // Read raw values, (humidity_raw, temperature_raw, status) → bool
                                                                    // 14-bit values; status 0 = valid, 1 = stale
    printf("raw RH %lu raw T %lu status %lu\r\n", (unsigned long)(rh_raw), (unsigned long)(t_raw), (unsigned long)(status));
    sensor.set_measurement_wait(60);                                // Override the post-request wait, (ms) → void
                                                                    // default 40 ms; lower resolutions need less
    sensor.set_measurement_wait(40);                                // Back to the default wait
    t = sensor.dew_point();                                         // Compute dew point, () → float °C
                                                                    // one measurement, Magnus formula
    printf("dew point %.2f C\r\n", t);
    // NVM access only works within 10 ms after power-up, so it needs the power_cycle helper above.
    ok = sensor.get_resolution(hb, tb, power_cycle);                // Get NVM resolution, (humidity_bits, temperature_bits, power_cycle) → bool
                                                                    // bits are 8, 10, 12 or 14
    printf("resolution %lu/%lu bit (ok=%lu)\r\n", (unsigned long)(hb), (unsigned long)(tb), (unsigned long)(ok));
    sensor.set_resolution(12, 12, power_cycle);                     // Set NVM resolution, (humidity_bits, temperature_bits, power_cycle) → bool
                                                                    // 8, 10, 12 or 14 bit; 0 leaves a channel unchanged
    ok = sensor.read_id(id, power_cycle);                           // Read the 32-bit sensor ID, (id, power_cycle) → bool
    printf("id 0x%lX (ok=%lu)\r\n", (unsigned long)(id), (unsigned long)(ok));
    sensor.enter_programming_mode();                                // Enter programming mode, () → void
                                                                    // only within 10 ms after power-up
    ok = sensor.read_nvm(0x06, reg);                                // Read an NVM register, (reg, value) → bool
                                                                    // 0x06 humidity / 0x11 temperature resolution, 0x1E / 0x1F ID
    printf("NVM 0x06 = 0x%lX (ok=%lu)\r\n", (unsigned long)(reg), (unsigned long)(ok));
    sensor.exit_programming_mode();                                 // Leave programming mode, () → void
    while (true) {
        HAL_Delay(1000);
    }
}
