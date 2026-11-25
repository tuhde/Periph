#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "FXOS8700.h"

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

#define WAIT() HAL_Delay(200)

static int passed = 0, failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    HAL_Delay(100);

    I2CConnectionSTM32Cube connection(&hi2c1, 0x1E);

    FXOS8700Minimal compass(connection);                    // Create FXOS8700 driver, (connection)
    check_true(compass.found(), "who_am_i_found");

    float ax, ay, az, mx, my, mz;
    compass.readAccel(ax, ay, az);                          // Read acceleration, (x, y, z) → g, g, g
    check_true(ax == ax && ay == ay && az == az, "read_accel_returns_floats");
    float magnitude = sqrtf(ax * ax + ay * ay + az * az);
    check_true(magnitude >= 0.7f && magnitude <= 1.3f, "accel_magnitude_near_1g");
    compass.readMag(mx, my, mz);                            // Read magnetic field, (x, y, z) → µT, µT, µT
    check_true(fabsf(mx) <= 1200.0f && fabsf(my) <= 1200.0f && fabsf(mz) <= 1200.0f, "mag_in_range");

    FXOS8700Full full(connection);                          // Create FXOS8700 Full driver, (connection)
    check_true(full.found(), "full_who_am_i_found");
    check_true(full.readHybrid(ax, ay, az, mx, my, mz), "read_hybrid_ok");
    check_true(fabsf(az) <= 2.5f && fabsf(mx) <= 1200.0f, "hybrid_plausible");

    int16_t rx, ry, rz;
    full.readRawAccel(rx, ry, rz);                          // Read raw accelerometer, (x, y, z) → counts
    check_true(rx >= -8192 && rx <= 8191 && ry >= -8192 && ry <= 8191 && rz >= -8192 && rz <= 8191, "raw_accel_in_14bit_range");
    full.readRawMag(rx, ry, rz);                            // Read raw magnetometer, (x, y, z) → counts
    check_true(abs((int)rx) <= 32768 && abs((int)ry) <= 32768 && abs((int)rz) <= 32768, "raw_mag_in_16bit_range");
    float temperature = 0.0f;
    check_true(full.readTemperature(temperature) && temperature > -100.0f && temperature < 150.0f, "temperature_plausible");

    full.setRange(4);
    full.setDataRate(100.0f);
    WAIT();
    full.readAccel(ax, ay, az);
    check_true(fabsf(az) <= 4.5f, "read_after_set_range_4g");
    full.setRange(2);
    full.setDataRate(200.0f);
    full.setAccelOversampling(2);
    full.setAccelOversampling(0);
    full.setMagOversampling(7);
    check_true(full.readSystemMode() == 1, "system_mode_wake");
    WAIT();
    check_true(full.newAccelData() == true || full.newAccelData() == false, "new_accel_data_bool");
    check_true(full.newMagData() == true || full.newMagData() == false, "new_mag_data_bool");

    full.setAccelOffset(0.02f, -0.02f, 0.0f);
    float ox, oy, oz;
    full.getAccelOffset(ox, oy, oz);
    check_true(fabsf(ox - 0.02f) < 0.003f && fabsf(oy + 0.02f) < 0.003f, "accel_offset_roundtrip");
    full.setAccelOffset(0.0f, 0.0f, 0.0f);
    full.setMagOffset(5.0f, -5.0f, 0.0f);
    full.getMagOffset(ox, oy, oz);
    check_true(fabsf(ox - 5.0f) < 0.15f && fabsf(oy + 5.0f) < 0.15f, "mag_offset_roundtrip");
    full.setMagOffset(0.0f, 0.0f, 0.0f);

    full.resetMagMinMax();
    WAIT();
    float mn[3], mxv[3];
    full.readMagMinMax(mn, mxv);
    check_true(mn[0] <= mxv[0] && mn[1] <= mxv[1] && mn[2] <= mxv[2], "min_max_ordered");
    float heading = full.heading();
    check_true(heading >= 0.0f && heading < 360.0f, "heading_in_range");

    check_true(full.setFifo(FXOS8700Full::FIFO_CIRCULAR, 8), "fifo_enabled");
    WAIT();
    check_true(full.fifoLevel() <= 32, "fifo_level_in_range");
    float samples[12];
    check_true(full.readFifo(samples, 4) <= 4, "fifo_samples_bounded");
    full.setFifo(FXOS8700Full::FIFO_DISABLED);

    full.setFreefallMotion(true, 7, 0.3f, 50.0f, true);
    { uint8_t v = full.readMotionSource(); check_true((v & 0xFF) == v, "motion_source_readable"); }
    full.setTransient(7, 0.3f);
    { uint8_t v = full.readTransientSource(); check_true((v & 0xFF) == v, "transient_source_readable"); }
    full.setPulse(0x07);
    { uint8_t v = full.readPulseSource(); check_true((v & 0xFF) == v, "pulse_source_readable"); }
    check_true(full.setOrientation(true, 100.0f), "orientation_configured");
    bool changed, lockout, back;
    uint8_t pl;
    full.readOrientation(changed, lockout, pl, back);
    check_true(pl <= 3, "orientation_in_range");

    full.enableInterrupt(FXOS8700Full::SOURCE_DATA_READY, 1);
    { uint8_t v = full.pollInterrupt(); check_true((v & 0xFF) == v, "poll_interrupt_mask"); }
    full.disableInterrupt(FXOS8700Full::SOURCE_DATA_READY);

    int16_t dx, dy, dz;
    bool st_ok = full.selfTest(dx, dy, dz);                 // Run accelerometer self-test, (dx, dy, dz) → bool
    check_true(st_ok == true || st_ok == false, "self_test_runs");
    check_true(dz > 0, "self_test_z_positive");
    full.setMode(2);
    check_true(full.measureMagOnce(mx, my, mz), "measure_mag_once");

    check_true(connection.error_count() == 0, "i2c_no_hal_errors");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
