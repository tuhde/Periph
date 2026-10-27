#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "LSM303.h"

#define LOG(...) printf(__VA_ARGS__)
static I2C_HandleTypeDef hi2c1;
static UART_HandleTypeDef huart2;

extern "C" void SysTick_Handler(void) { HAL_IncTick(); }
extern "C" int _write(int file, char* ptr, int len) {
    (void)file;
    HAL_UART_Transmit(&huart2, (uint8_t*)ptr, (uint16_t)len, HAL_MAX_DELAY);
    return len;
}

static void lsm303_sleep_ms(uint32_t ms) { HAL_Delay(ms); }
static uint32_t lsm303_now_ms() { return HAL_GetTick(); }
static void on_int(uint16_t status) { printf("interrupt status: 0x%04X\n", status); }

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
    HAL_Delay(100);
    I2CConnectionSTM32Cube accel_connection(&hi2c1, 0x19);
    I2CConnectionSTM32Cube mag_connection(&hi2c1, 0x1E);
    LSM303Full compass(accel_connection, mag_connection);
    // --- Accelerometer range, mode, data rate, axes ---
    compass.set_range(8);                                   // Set accelerometer range, (range_g) -> g
    compass.set_mode(1);                                    // Set accelerometer mode, (mode) -> None
    compass.set_data_rate(400);                             // Set accelerometer data rate, (rate_hz) -> Hz
    compass.set_axes(true, true, true);                     // Enable accelerometer axes, (x, y, z) -> None

    // --- Power cycle and restore defaults ---
    compass.power_down();                                   // Stop accelerometer conversions, () -> None
    compass.set_data_rate(100);                             // Set accelerometer data rate, (rate_hz) -> Hz
    compass.set_mode(2);                                    // Set accelerometer mode, (mode) -> None
    compass.set_range(2);                                   // Set accelerometer range, (range_g) -> g

    // --- Reads ---
    float ax, ay, az;
    compass.read_acceleration(ax, ay, az);                  // Read 3-axis acceleration, (x, y, z) -> g, g, g
    int16_t rx, ry, rz;
    compass.read_acceleration_raw(rx, ry, rz);              // Read raw acceleration counts, (x, y, z) -> int, int, int
    bool ready = compass.new_data_available();              // Check new acceleration data, () -> bool
    uint8_t status = compass.read_status();                 // Read accelerometer status, () -> int

    // --- High-pass filter ---
    compass.set_high_pass(1, 2, true, true, false, true);   // Configure high-pass filter, (mode, cutoff, filter_output, on_ia1, on_ia2, on_click) -> None
    uint8_t ref = compass.reset_high_pass();                // Reset high-pass filter, () -> int
    compass.set_reference(0x40);                            // Set high-pass reference, (value) -> None

    // --- FIFO ---
    compass.set_fifo(LSM303Full::FIFO_FIFO, 16, false);     // Configure FIFO, (mode, watermark, trigger_int2) -> None
    uint8_t level = compass.fifo_level();                   // Read FIFO level, () -> int
    bool wm, ov, empty;
    uint8_t lvl;
    compass.read_fifo_status(wm, ov, empty, lvl);           // Read FIFO status, () -> (bool, bool, bool, int)
    float xb[8], yb[8], zb[8];
    uint8_t n = compass.read_fifo(xb, yb, zb, 8);           // Drain FIFO, (x_buf, y_buf, z_buf, max_samples) -> int
    compass.set_fifo(LSM303Full::FIFO_BYPASS);              // Configure FIFO, (mode, watermark, trigger_int2) -> None

    // --- Inertial interrupt generators ---
    compass.set_motion_interrupt(1, 0x3F, 0.5f, 30, true); // Configure motion interrupt, (generator, events, threshold_g, duration_ms, and_mode) -> None
    compass.set_free_fall(1, 0.35f, 30);                    // Configure free-fall, (generator, threshold_g, duration_ms) -> None
    compass.set_orientation(1, false, false);               // Configure orientation, (generator, position, four_d) -> None
    compass.set_latch(1, true);                             // Latch generator 1, (generator, enabled) -> None
    uint8_t src1 = compass.read_interrupt_source(1);       // Read interrupt source, (generator) -> int
    compass.set_latch(1, false);                            // Latch generator 1, (generator, enabled) -> None

    // --- Click detection ---
    compass.set_click(0x01, 0x04, 0.5f, 10, 20, 50);        // Configure click detection, (single, double_, threshold_g, time_limit_ms, latency_ms, window_ms) -> None
    uint8_t click = compass.read_click_source();            // Read click source, () -> int

    // --- Interrupt routing and delivery ---
    compass.set_interrupt_polarity(false);                  // Set interrupt polarity, (active_low) -> None
    compass.enable_interrupt(LSM303Full::SOURCE_DATA_READY, 1);  // Enable interrupt source, (source, pin) -> None
    compass.enable_interrupt(LSM303Full::SOURCE_CLICK, 1);  // Enable interrupt source, (source, pin) -> None
    compass.disable_interrupt(LSM303Full::SOURCE_DATA_READY, 1); // Disable interrupt source, (source, pin) -> None
    compass.on_interrupt(on_int, 1);                        // Subscribe to INT1, (callback, pin) -> None
    uint16_t isr = compass.poll_interrupt();                // Poll interrupt status, () -> int
    compass.off_interrupt(1);                               // Unsubscribe from INT1, (pin) -> None

    // --- Magnetometer ---
    compass.set_mag_range(470);                             // Set magnetometer range, (range_ut) -> uT
    compass.set_mag_data_rate(30.0f);                       // Set magnetometer data rate, (rate_hz) -> Hz
    compass.set_mag_mode(1);                                // Set magnetometer mode, (mode) -> None
    compass.set_mag_mode(0);                                // Set magnetometer mode, (mode) -> None
    int16_t mrx, mry, mrz;
    compass.read_magnetic_raw(mrx, mry, mrz);               // Read raw magnetic counts, (x, y, z) -> int, int, int
    float mx, my, mz;
    compass.read_magnetic(mx, my, mz);                      // Read 3-axis magnetic field, (x, y, z) -> uT, uT, uT
    bool mready = compass.mag_data_ready();                 // Check new magnetometer data, () -> bool
    bool drdy, lock;
    compass.read_mag_status(drdy, lock);                    // Read magnetometer status, () -> (bool, bool)
    uint8_t identity[3];
    compass.read_mag_identity(identity);                    // Read magnetometer identity, () -> bytes
    compass.enable_temperature(true);                       // Enable temperature sensor, (enabled) -> None
    float temp = compass.read_temperature_delta();          // Read relative temperature, () -> float C
    float heading = compass.heading(true);                  // Compute compass heading, (tilt_compensate) -> deg
    float flat = compass.heading(false);                    // Compute compass heading, (tilt_compensate) -> deg

    // --- System ---
    compass.reboot();                                       // Reboot accelerometer memory, () -> None

    LOG("accel: x=%.3f y=%.3f z=%.3f g\n", ax, ay, az);
    LOG("raw: %d %d %d  ready=%d status=0x%02X\n", rx, ry, rz, ready ? 1 : 0, status);
    LOG("reference=0x%02X fifo level=%d wm=%d ov=%d empty=%d read=%d\n",
        ref, level, wm ? 1 : 0, ov ? 1 : 0, empty ? 1 : 0, n);
    LOG("int1_src=0x%02X click_src=0x%02X isr=0x%04X\n", src1, click, isr);
    LOG("mag: x=%.1f y=%.1f z=%.1f uT  raw=%d %d %d\n", mx, my, mz, mrx, mry, mrz);
    LOG("mag ready=%d drdy=%d lock=%d id=%02X %02X %02X\n",
        mready ? 1 : 0, drdy ? 1 : 0, lock ? 1 : 0, identity[0], identity[1], identity[2]);
    LOG("temp=%+.1f C  heading=%.1f deg  flat=%.1f deg\n", temp, heading, flat);
    return 0;
}
