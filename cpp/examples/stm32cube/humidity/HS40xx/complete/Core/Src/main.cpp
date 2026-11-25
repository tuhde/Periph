#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "HS40xx.h"

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

static volatile uint8_t alert_status = 0;
static void on_alert(uint8_t status) { alert_status = status; }

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x54);
    HS40xxFull sensor(connection);                                  // Create HS40xx driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0;
    uint8_t hb = 0, tb = 0, st = 0, reg = 0;
    uint32_t id = 0;
    sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                    // no-hold 0xF5, 5 bytes, CRC verified; one retry on mismatch
    printf("%.2f C, %.1f %%RH\r\n", t, rh);
    t = sensor.read_temperature();                                  // Read temperature only, () → float °C
                                                                    // no-hold 0xF3, 3 bytes; NAN on failure
    printf("temperature %.2f C\r\n", t);
    rh = sensor.read_humidity();                                    // Read humidity only, () → float %RH
                                                                    // NAN on failure
    printf("humidity %.1f %%RH\r\n", rh);
    sensor.read_hold(t, rh);                                        // Read with clock stretching, (t, rh) → bool °C, %RH
                                                                    // 0xE5; needs a stretching controller and SCL >= 200 kHz
    sensor.read_raw(rh_raw, t_raw);                                 // Read raw values, (humidity_raw, temperature_raw) → bool
                                                                    // 14-bit values, CRC verified
    printf("raw RH %lu raw T %lu\r\n", (unsigned long)(rh_raw), (unsigned long)(t_raw));
    sensor.set_resolution(12, 12);                                  // Set resolution, (humidity_bits, temperature_bits) → bool
                                                                    // 8, 10, 12 or 14 bit; 0 leaves a channel unchanged
    sensor.get_resolution(hb, tb);                                  // Get resolution, (humidity_bits, temperature_bits) → bool
    printf("resolution %lu/%lu bit\r\n", (unsigned long)(hb), (unsigned long)(tb));
    sensor.read_register(0x00, reg);                                // Read a register, (reg, value) → bool
    sensor.write_register(0x00, 0x0F);                              // Write a register, (reg, value) → bool
                                                                    // 0x0F = 14 bit for both channels (default)
    sensor.set_alert_thresholds(60.0f, 55.0f, NAN, NAN, 40.0f, 38.0f);// Set alert thresholds, (rh_high, rh_high_reset, rh_low, rh_low_reset, temp_high, temp_high_reset, temp_low, temp_low_reset) → bool
                                                                    // %RH / °C; NAN leaves a threshold unchanged
    sensor.set_alert_polarity(true);                                // Set ALERT polarity, (active_high) → bool
    sensor.enable_interrupt(HS40xxFull::SOURCE_RH_HIGH | HS40xxFull::SOURCE_TEMP_HIGH);// Enable alert sources, (source) → bool
    sensor.start_periodic(1.0f);                                    // Start periodic mode, (period_s) → bool
                                                                    // 0.5, 1 or 2.5 s
    HAL_Delay(1500);
    sensor.read_periodic(t, rh, st);                                // Fetch the latest periodic result, (t, rh, status) → bool
                                                                    // status is a SOURCE_* bitmask
    printf("periodic %.2f C %.1f %%RH status %lu\r\n", t, rh, (unsigned long)(st));
    st = sensor.poll_interrupt();                                   // Read alert status, () → uint8_t
                                                                    // SOURCE_* bitmask, 0 on failure
    sensor.on_interrupt(on_alert);                                  // Subscribe to the ALERT pin, (callback, intPin = nullptr) → void
                                                                    // needs an InputPin on the connection (or passed in)
    HAL_Delay(5000);
    sensor.off_interrupt();                                         // Unsubscribe, () → void
    sensor.disable_interrupt(HS40xxFull::SOURCE_RH_HIGH | HS40xxFull::SOURCE_TEMP_HIGH);// Disable alert sources, (source) → bool
    sensor.stop_periodic();                                         // Stop periodic mode, () → bool
    sensor.read_id(id);                                             // Read the 32-bit sensor ID, (id) → bool
    printf("id 0x%lX\r\n", (unsigned long)(id));
    t = sensor.dew_point();                                         // Compute dew point, () → float °C
                                                                    // one measurement, Magnus formula
    printf("dew point %.2f C\r\n", t);
    sensor.soft_reset();                                            // Soft reset, () → bool
                                                                    // stops periodic mode, waits 500 ms
    while (true) {
        HAL_Delay(1000);
    }
}
