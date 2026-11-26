#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Spl06.h"

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

static void on_int(uint8_t status) {
    printf("interrupt 0x%02X\r\n", (unsigned)(status));
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x77);
    SPL06Full sensor(connection);                                  // Create SPL06 driver, (connection) → void
                                                                   // stores the connection only; no bus traffic yet
    sensor.init();                                                 // Check ID, read coefficients, apply defaults, () → bool
                                                                   // ID 0x10, waits for COEF_RDY, 16x pressure / 1x external temperature
    SPL06Coefficients co;
    sensor.coefficients(co);                                       // Decoded calibration, (coefficients&) → void
                                                                   // the nine factory coefficients used by every compensation
    printf("c0 %ld c1 %ld c00 %ld c10 %ld\r\n", (long)(co.c0), (long)(co.c1), (long)(co.c00), (long)(co.c10));
    printf("c01 %ld c11 %ld c20 %ld c21 %ld c30 %ld\r\n", (long)(co.c01), (long)(co.c11), (long)(co.c20), (long)(co.c21), (long)(co.c30));
    float t = 0, p = 0, alt = 0;
    bool ok = sensor.read(t, p);                                   // Temperature and pressure, (temperature_c&, pressure_pa&) → bool
                                                                   // command mode: one temperature, then one pressure measurement
    printf("read %.2f C %.2f Pa\r\n", (double)(t), (double)(p));
    ok = sensor.read_pressure(p);                                  // Pressure, (pressure_pa&) → bool
                                                                   // measures temperature first, because the compensation needs it
    ok = sensor.read_temperature(t);                               // Temperature, (temperature_c&) → bool
                                                                   // one temperature measurement only
    printf("pressure %.2f Pa temperature %.2f C\r\n", (double)(p), (double)(t));
    int32_t praw = 0, traw = 0;
    ok = sensor.read_raw(praw, traw);                              // Raw results, (praw&, traw&) → bool
                                                                   // signed 24-bit values from the result registers
    printf("raw %ld %ld\r\n", (long)(praw), (long)(traw));
    ok = sensor.compensate(praw, traw, 16, 1, t, p);               // Compensate raw values, (praw, traw, p_oversampling, t_oversampling, temperature_c&, pressure_pa&) → bool
                                                                   // pure computation with the stored coefficients; no bus access
    printf("compensated %.2f C %.2f Pa\r\n", (double)(t), (double)(p));
    ok = sensor.altitude(alt, 101325.0f);                          // Altitude, (altitude_m&, sea_level_pa = 101325) → bool
                                                                   // international barometric formula against the given sea-level pressure
    printf("altitude %.2f m\r\n", (double)(alt));
    ok = sensor.set_pressure_config(64, 4);                        // Pressure oversampling and rate, (oversampling = 16, rate = 1) → bool
                                                                   // 64x oversampling, 4 background readings per second; sets P_SHIFT
    ok = sensor.set_temperature_config(2, 4);                      // Temperature oversampling and rate, (oversampling = 1, rate = 1) → bool
                                                                   // 2x oversampling, 4 per second; always keeps the external sensor
    ok = sensor.start_continuous(true, true);                      // Background mode, (pressure = true, temperature = true) → bool
                                                                   // MEAS_CTRL = 111: the chip measures both on its own
    ok = sensor.read_continuous(t, p);                             // Latest background result, (temperature_c&, pressure_pa&) → bool
                                                                   // waits for both ready flags; does not start a measurement
    printf("continuous %.2f C %.2f Pa\r\n", (double)(t), (double)(p));
    bool pr = false, tr = false;
    ok = sensor.data_ready(pr, tr);                                // Ready flags, (pressure_ready&, temperature_ready&) → bool
                                                                   // MEAS_CFG.PRS_RDY and TMP_RDY
    printf("ready %lu %lu\r\n", (unsigned long)(pr), (unsigned long)(tr));
    ok = sensor.stop();                                            // Standby, () → bool
                                                                   // MEAS_CTRL = 000
    ok = sensor.enable_fifo();                                     // FIFO on, () → bool
                                                                   // background results now queue in the 32-entry FIFO
    ok = sensor.start_continuous();                                // Background mode, (pressure = true, temperature = true) → bool
                                                                   // fill the FIFO for a second
    HAL_Delay(1000);
    ok = sensor.stop();                                            // Standby, () → bool
                                                                   // stop adding entries
    bool empty = false, full = false;
    ok = sensor.fifo_status(empty, full);                          // FIFO state, (empty&, full&) → bool
                                                                   // FIFO_STS flags
    printf("fifo empty %lu full %lu\r\n", (unsigned long)(empty), (unsigned long)(full));
    SPL06Full::FifoEntry entries[32];
    uint8_t n = sensor.read_fifo(entries, 32);                     // Drain FIFO, (entries*, max_entries) → uint8_t
                                                                   // each entry is KIND_PRESSURE (Pa) or KIND_TEMPERATURE (C), in measured order
    printf("fifo %lu entries\r\n", (unsigned long)(n));
    ok = sensor.flush_fifo();                                      // Flush FIFO, () → bool
                                                                   // discards any remaining entries
    ok = sensor.disable_fifo();                                    // FIFO off, () → bool
                                                                   // results go to the result registers again
    ok = sensor.enable_interrupt(SPL06Full::SOURCE_PRESSURE);      // Enable interrupt source, (source) → bool
                                                                   // pressure-ready interrupt on SDO (active low)
    printf("INT_STS 0x%02X\r\n", (unsigned)(sensor.poll_interrupt()));
    // poll_interrupt(): read and clear INT_STS, () → uint8_t
    sensor.on_interrupt(on_int);                                   // Subscribe, (callback, intPin = nullptr) → void
                                                                   // does nothing without an INT line wired; poll instead
    sensor.off_interrupt();                                        // Unsubscribe, () → void
                                                                   // removes the edge handler
    ok = sensor.disable_interrupt(SPL06Full::SOURCE_PRESSURE);     // Disable interrupt source, (source) → bool
                                                                   // clears CFG_REG.INT_PRS
    ok = sensor.reset();                                           // Soft reset, () → bool
                                                                   // RESET = 0x09, re-reads the coefficients and restores the init defaults
    printf("ok %lu\r\n", (unsigned long)(ok));
    while (1) {}
}
