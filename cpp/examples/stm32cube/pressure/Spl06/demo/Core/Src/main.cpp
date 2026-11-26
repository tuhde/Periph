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
    // --- Identity and calibration ---
    // init() checks ID = 0x10; the nine coefficients are factory-trimmed per
    // part and drive every compensated value below.
    sensor.init();                                                 // Check ID, read coefficients, apply defaults, () → bool
    SPL06Coefficients co;
    sensor.coefficients(co);                                       // Decoded calibration, (coefficients&) → void
    printf("ID 0x10 verified; c0 %ld c1 %ld c00 %ld c10 %ld c01 %ld\r\n", (long)(co.c0), (long)(co.c1), (long)(co.c00), (long)(co.c10), (long)(co.c01));

    // --- Altimeter: 20 s at 1 Hz, altitude relative to the first reading ---
    // The first pressure becomes the reference level, so the altitude column
    // shows how far the sensor moved since start (about 12 Pa per metre).
    float t = 0, t2 = 0, p = 0, h = 0, p0 = 0;
    sensor.read_pressure(p0);                                      // Reference pressure, (pressure_pa&) → bool
    for (int i = 0; i < 20; i++) {
        sensor.read(t, p);                                         // Temperature and pressure, (temperature_c&, pressure_pa&) → bool
        sensor.read_temperature(t2);                               // Temperature only, (temperature_c&) → bool
        sensor.altitude(h, p0);                                    // Altitude vs. reference, (altitude_m&, sea_level_pa) → bool
        printf("%ld s  %.2f C (%.2f C)  %.2f Pa  %.2f m\r\n", (long)(i), (double)(t), (double)(t2), (double)(p), (double)(h));
        HAL_Delay(1000);
    }

    // --- Oversampling: noise and measurement time at 1x, 16x and 128x ---
    // More oversampling means less noise but longer measurements; above 8x
    // the driver sets P_SHIFT automatically.
    static const uint8_t osrs[3] = {1, 16, 128};
    for (int k = 0; k < 3; k++) {
        sensor.set_pressure_config(osrs[k], 1);                    // Pressure oversampling, (oversampling, rate) → bool
        float v[10];
        unsigned long start = HAL_GetTick();
        for (int j = 0; j < 10; j++) {
            sensor.read_pressure(v[j]);                            // Pressure, (pressure_pa&) → bool
        }
        unsigned long per = (HAL_GetTick() - start) / 10;
        float mean = 0, var = 0;
        for (int j = 0; j < 10; j++) mean += v[j] / 10.0f;
        for (int j = 0; j < 10; j++) var += (v[j] - mean) * (v[j] - mean) / 10.0f;
        printf("%lux: mean %.2f Pa  sd %.2f Pa  %lu ms per reading\r\n", (unsigned long)(osrs[k]), (double)(mean), (double)(sqrtf(var)), (unsigned long)(per));
    }

    // --- Variometer: background mode at 8 Hz ---
    // The chip measures on its own; read_continuous only fetches the latest
    // results, so the host never waits for a conversion it started itself.
    sensor.set_pressure_config(16, 8);                             // 16x, 8 Hz, (oversampling, rate) → bool
    sensor.set_temperature_config(1, 8);                           // 1x, 8 Hz, (oversampling, rate) → bool
    sensor.start_continuous(true, true);                           // Background mode, (pressure, temperature) → bool
    float last = 0;
    for (int i = 0; i < 16; i++) {
        sensor.read_continuous(t, p);                              // Latest results, (temperature_c&, pressure_pa&) → bool
        if (i > 0) {
            printf("%.2f Pa  climb %.2f m/s\r\n", (double)(p), (double)(-(p - last) / 12.0f * 8.0f));
        }
        last = p;
    }
    bool pr = false, tr = false;
    sensor.data_ready(pr, tr);                                     // Ready flags, (pressure_ready&, temperature_ready&) → bool
    printf("ready flags %lu %lu\r\n", (unsigned long)(pr), (unsigned long)(tr));
    sensor.stop();                                                 // Standby, () → bool

    // --- FIFO: let the chip collect 32 results, then read them in one go ---
    // Each entry's LSB marks it as pressure or temperature; the host can sleep
    // while the chip fills the FIFO (32 entries = 2 s at 8 Hz for both).
    sensor.flush_fifo();                                           // Empty the FIFO, () → bool
    sensor.enable_fifo();                                          // FIFO on, () → bool
    sensor.start_continuous();                                     // Background mode, (pressure = true, temperature = true) → bool
    bool empty = true, full = false;
    for (int i = 0; i < 50 && !full; i++) {
        sensor.fifo_status(empty, full);                           // Wait for full, (empty&, full&) → bool
        HAL_Delay(100);
    }
    sensor.stop();                                                 // Standby, () → bool
    static SPL06Full::FifoEntry entries[32];
    uint8_t n = sensor.read_fifo(entries, 32);                     // Drain FIFO, (entries*, max_entries) → uint8_t
    float psum = 0;
    unsigned np = 0;
    for (uint8_t i = 0; i < n; i++) {
        if (entries[i].kind == SPL06Full::KIND_PRESSURE) { psum += entries[i].value; np++; }
    }
    printf("%lu FIFO entries, %lu pressure, mean %.2f Pa\r\n", (unsigned long)(n), (unsigned long)(np), (double)(np ? psum / np : 0.0f));
    sensor.disable_fifo();                                         // FIFO off, () → bool

    // --- Data-ready interrupt ---
    // SDO doubles as the interrupt line; the callback receives the INT_STS bits.
    // Without an INT pin nothing is delivered, so the loop also polls INT_STS.
    sensor.enable_interrupt(SPL06Full::SOURCE_PRESSURE);           // Pressure-ready interrupt, (source) → bool
    sensor.poll_interrupt();                                       // Clear stale status, () → uint8_t
    sensor.on_interrupt(on_int);                                   // Subscribe, (callback, intPin = nullptr) → void
    sensor.start_continuous(true, true);                           // Background mode, (pressure, temperature) → bool
    for (int i = 0; i < 4; i++) {
        HAL_Delay(500);
        uint8_t status = sensor.poll_interrupt();                  // Read and clear INT_STS, () → uint8_t
        printf("INT_STS 0x%02X\r\n", (unsigned)(status));
    }
    sensor.off_interrupt();                                        // Unsubscribe, () → void
    sensor.disable_interrupt(SPL06Full::SOURCE_PRESSURE);          // Disable source, (source) → bool
    sensor.stop();                                                 // Standby, () → bool

    // --- Compensation by hand ---
    // compensate() turns raw register values into SI units with the stored
    // coefficients; useful for logging raw data and converting it later.
    sensor.set_pressure_config(16, 1);                             // Back to 16x, (oversampling, rate) → bool
    sensor.set_temperature_config(1, 1);                           // Back to 1x, (oversampling, rate) → bool
    int32_t praw = 0, traw = 0;
    sensor.read_raw(praw, traw);                                   // Raw results, (praw&, traw&) → bool
    sensor.compensate(praw, traw, 16, 1, t, p);                    // Compensate, (praw, traw, p_oversampling, t_oversampling, temperature_c&, pressure_pa&) → bool
    printf("raw %ld %ld -> %.2f C %.2f Pa\r\n", (long)(praw), (long)(traw), (double)(t), (double)(p));

    // --- Wrap up ---
    // Standby, empty FIFO and a soft reset leave the chip as after power-up
    // (plus the driver defaults).
    sensor.stop();                                                 // Standby, () → bool
    sensor.flush_fifo();                                           // Empty the FIFO, () → bool
    sensor.reset();                                                // Soft reset and defaults, () → bool
    while (1) {}
}
