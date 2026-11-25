#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "ADXL345.h"

static I2C_HandleTypeDef hi2c1;
static UART_HandleTypeDef huart2;

extern "C" void SysTick_Handler(void) {
    HAL_IncTick();
}

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

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();

    I2CConnectionSTM32Cube connection(&hi2c1, 0x53);
    ADXL345Full accel(connection);                            // Create ADXL345 Full driver, (connection)

    accel.set_range(4);                                       // Set measurement range, (range_g) → g
                                                                // selects ±4 g; FULL_RES is preserved so scale stays 3.9 mg/LSB
    accel.set_data_rate(200);                                 // Set output data rate, (rate_hz) → Hz
                                                                // picks the nearest supported value (200 Hz)
    accel.set_low_power(false);                               // Set low-power mode, (enabled) → None
                                                                // normal-power mode; LOW_POWER bit in BW_RATE cleared
    accel.calibrate_offset(0.0f, 0.0f, 1.0f, 64);             // Calibrate offsets, (target_x=0 g, target_y=0 g, target_z=1 g, samples=128) → g, g, g
                                                                // averages 64 samples with Z axis up and writes OFSX/OFSY/OFSZ
    accel.set_tap_detection(0.5f, 10.0f);                     // Configure single-tap, (threshold_g, duration_ms, axes=0x07, suppress=false) → g, ms
                                                                // 0.5 g threshold, 10 ms duration, all axes, no suppress
    accel.set_double_tap(50.0f, 200.0f);                      // Configure double-tap, (latency_ms, window_ms) → ms, ms
                                                                // 50 ms latency, 200 ms window between taps
    accel.set_fifo_mode(ADXL345Full::FIFO_STREAM, 16);        // Configure FIFO, (mode, samples=16) → None
                                                                // stream mode, watermark 16 entries
    accel.set_interrupt(ADXL345Full::INT_WATERMARK, true, 1); // Configure interrupt, (source, enabled, pin=1) → None
                                                                // enable watermark interrupt on INT1

    float x, y, z;
    accel.read(x, y, z);                                      // Read 3-axis acceleration, (x, y, z) → g, g, g
                                                                // single-shot burst read of all 6 data bytes

    float xs[32], ys[32], zs[32];
    uint8_t n = accel.read_fifo(xs, ys, zs, 32);              // Drain the FIFO, (x_buf, y_buf, z_buf, max_samples) → count
                                                                // pulls every buffered sample out of the 32-level FIFO
    uint8_t count = accel.fifo_count();                       // FIFO entries available, () → count
                                                                // reads FIFO_STATUS entries[5:0]
    uint8_t src = accel.read_interrupt_source();              // Read interrupt source, () → bitmask
                                                                // reading INT_SOURCE also clears the latched interrupts

    accel.self_test(false);                                   // Toggle self-test, (enabled) → None
                                                                // electrostatic self-test force left off for normal operation
    accel.set_sleep(false);                                   // Set sleep mode, (enabled, wakeup_hz=8) → Hz
                                                                // stays in normal measurement mode
    accel.set_link_mode(false);                               // Set activity/inactivity link, (enabled) → None
                                                                // link mode off; activity/inactivity run independently
    accel.set_auto_sleep(false);                              // Set auto-sleep, (enabled) → None
                                                                // auto-sleep requires Link=1, so this stays off too

    printf("fifo_count=%u interrupts=0x%02X\r\n", count, src);
    (void)n;
    while (true) HAL_Delay(1000);
}
