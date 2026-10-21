#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "LIS3DH.h"

static I2C_HandleTypeDef hi2c1;
static UART_HandleTypeDef huart2;

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

static void lis3dh_report(float pitch, float roll, float mag, int16_t temp, uint8_t level) {
    printf("pitch=%+.1f roll=%+.1f |a|=%.3f g  T=%d C  fifo=%d\r\n",
           pitch, roll, mag, temp, level);
}

static void lis3dh_event(const char* what) {
    printf("%s\r\n", what);
}

static uint32_t lis3dh_now_ms() { return HAL_GetTick(); }

static void lis3dh_sleep_ms(uint32_t ms) { HAL_Delay(ms); }

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    HAL_Delay(100);

    I2CConnectionSTM32Cube connection(&hi2c1, 0x18);
    LIS3DHFull accel(connection);                            // Create LIS3DH driver, (connection)
    // --- Configure ±2 g / 50 Hz high-resolution and verify the part ---
    // The self-test only passes on healthy silicon, so run it before
    // arming anything; 50 Hz keeps the self-test window short (2 samples).
    accel.set_data_rate(50);                                 // Set output data rate, (rate_hz) → Hz
    accel.set_range(2);                                      // Set full-scale range, (range_g) → g
    accel.set_mode(2);                                       // Set operating mode, (mode) → None
    bool st = accel.self_test(1);                            // Run self-test, (mode) → bool
    printf("self-test: %s\n", st ? "PASS" : "FAIL");

    // --- Arm the double-tap and free-fall detectors on INT1 ---
    // Both sources are latched so a transient cannot slip past the 100 ms
    // polling loop; the click window (200 ms) covers a slow double tap.
    accel.set_click(0, 0x04, 0.8f, 20, 40, 200, true);       // Configure click detection, (single, double_, threshold_g, time_limit_ms, latency_ms, window_ms, latch) → None
                                                            // double tap on Z with an 0.8 g threshold
    accel.set_free_fall(1, 0.35f, 60);                       // Configure free-fall, (generator, threshold_g, duration_ms) → None
                                                            // 0.35 g threshold held for 60 ms
    accel.set_latch(1, true);                                // Latch generator 1, (generator, enabled) → None
    accel.enable_interrupt(LIS3DHFull::SOURCE_CLICK, 1);    // Route source to INT pin, (source, pin) → None
    accel.enable_interrupt(LIS3DHFull::SOURCE_IA1, 1);      // Route source to INT pin, (source, pin) → None

    // --- Run the FIFO in stream mode with a 16-sample watermark ---
    // Stream mode overwrites the oldest samples once full, so a slow
    // reader always sees recent motion instead of going stale.
    accel.set_fifo(LIS3DHFull::FIFO_STREAM, 16, false);      // Configure FIFO, (mode, watermark, trigger_int2) → None
                                                            // stream mode with a 16-sample watermark

    // --- 60-second tilt / tap / free-fall monitor ---
    // The board is tilted, double-tapped and dropped within the 60 s
    // window. Between events the vector magnitude sits at ≈1.00 g.
    uint32_t start = lis3dh_now_ms();
    uint32_t last = start;
    while (lis3dh_now_ms() - start < 60000) {
        uint32_t now = lis3dh_now_ms();

        // Drain the FIFO every 100 ms and characterise the average attitude.
        if (now - last >= 100) {
            uint8_t level = accel.fifo_level();              // Read FIFO level, () → int
            float xb[32], yb[32], zb[32];
            uint8_t n = accel.read_fifo(xb, yb, zb, 32);     // Drain FIFO, (x_buf, y_buf, z_buf, max_samples) → int
            if (n > 0) {
                float ax = 0, ay = 0, az = 0;
                for (uint8_t i = 0; i < n; i++) { ax += xb[i]; ay += yb[i]; az += zb[i]; }
                ax /= n; ay /= n; az /= n;
                float mag = sqrtf(ax * ax + ay * ay + az * az);
                float pitch = atan2f(ax, sqrtf(ay * ay + az * az)) * 57.2957795f;
                float roll = atan2f(ay, az) * 57.2957795f;
                int16_t temp = accel.read_temperature_delta();  // Read relative temperature, () → int °C
                lis3dh_report(pitch, roll, mag, temp, level);
            }

            uint16_t isr = accel.poll_interrupt();           // Read interrupt status, () → int
            if (isr & LIS3DHFull::SOURCE_CLICK) lis3dh_event("DOUBLE TAP");
            if (isr & LIS3DHFull::SOURCE_IA1)   lis3dh_event("FREE FALL");
            last = now;
        }

        lis3dh_sleep_ms(10);
    }
    printf("done\n");
    return 0;
}
