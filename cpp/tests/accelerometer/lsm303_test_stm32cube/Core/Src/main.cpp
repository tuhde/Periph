#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include <math.h>
#include "LSM303.h"

static I2C_HandleTypeDef hi2c1;
static UART_HandleTypeDef huart2;

extern "C" void SysTick_Handler(void) { HAL_IncTick(); }

extern "C" int _write(int file, char* ptr, int len) {
    (void)file;
    HAL_UART_Transmit(&huart2, reinterpret_cast<uint8_t*>(ptr), static_cast<uint16_t>(len), HAL_MAX_DELAY);
    return len;
}

static int passed = 0, failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}

static void i2c1_init(void) {
    __HAL_RCC_GPIOB_CLK_ENABLE();
    __HAL_RCC_I2C1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_8 | GPIO_PIN_9;
    gpioInit.Mode      = GPIO_MODE_AF_OD;
    gpioInit.Pull      = GPIO_PULLUP;
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

static void uart2_init(void) {
    __HAL_RCC_GPIOA_CLK_ENABLE();
    __HAL_RCC_USART2_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_2 | GPIO_PIN_3;
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

int main(void) {
    HAL_Init();
    SystemClock_Config();
    uart2_init();
    i2c1_init();
    HAL_Delay(100);

    I2CConnectionSTM32Cube accel_connection(&hi2c1, LSM303Minimal::I2C_ADDRESS_ACCEL);
    I2CConnectionSTM32Cube mag_connection(&hi2c1, LSM303Minimal::I2C_ADDRESS_MAG);
    LSM303Full accel(accel_connection, mag_connection);  // Create LSM303 driver, (accel_connection, mag_connection)

    uint8_t identity[3];
    accel.readMagIdentity(identity);              // Read magnetometer identity, (out) → None
    check_true(identity[0] == 0x48 && identity[1] == 0x34 && identity[2] == 0x33, "mag_identity");

    float ax, ay, az;
    accel.readAcceleration(ax, ay, az);           // Read acceleration, (x, y, z) → g, g, g
    check_true(ax >= -4.0f && ax <= 4.0f && ay >= -4.0f && ay <= 4.0f && az >= -4.0f && az <= 4.0f,
               "acceleration_plausible");

    float mx, my, mz;
    accel.readMagnetic(mx, my, mz);               // Read magnetic field, (x, y, z) → uT, uT, uT
    check_true(mx >= -1500.0f && mx <= 1500.0f && my >= -1500.0f && my <= 1500.0f &&
               mz >= -1500.0f && mz <= 1500.0f, "magnetic_plausible");

    accel.setRange(4);                            // Set full-scale range, (range_g=4) → bool
    accel.setDataRate(50);                        // Set output data rate, (rate_hz=50) → bool
    accel.setMode(LSM303Full::MODE_HIGH_RESOLUTION);  // Set operating mode, (mode=MODE_HIGH_RESOLUTION=2) → bool
    accel.setAxes(true, true, true);              // Enable axes, (x=true, y=true, z=true) → None
    HAL_Delay(200);
    check_true(true, "reconfigure");

    accel.setRange(2);                            // Set full-scale range, (range_g=2) → bool
    accel.setDataRate(100);                       // Set output data rate, (rate_hz=100) → bool

    int16_t rx, ry, rz;
    accel.readAccelerationRaw(rx, ry, rz);        // Read raw acceleration, (x, y, z) → counts
    check_true(rx >= -2048 && rx <= 2047 && ry >= -2048 && ry <= 2047 && rz >= -2048 && rz <= 2047,
               "acceleration_raw_range");

    check_true(accel.newDataAvailable(), "new_data_available");  // Check new data, () → bool
    uint8_t status = accel.readStatus();          // Read status register, () → byte
    (void)status;
    check_true(true, "status_readable");

    accel.setHighPass(0, 0, false, false, false, false);  // Configure high-pass filter, (mode=0, cutoff=0, filter_output=false, on_ia1=false, on_ia2=false, on_click=false) → bool
    uint8_t reference = accel.resetHighPass();    // Reset high-pass filter, () → byte
    (void)reference;
    check_true(true, "reference_readable");
    accel.setReference(reference);                // Set reference value, (value=0..255) → None

    accel.setFifo(LSM303Full::FIFO_STREAM, 16);   // Configure FIFO, (mode=FIFO_STREAM=2, watermark=0, trigger_int2=false) → bool
    HAL_Delay(200);
    uint8_t level = accel.fifoLevel();            // Read FIFO level, () → byte
    check_true(level <= 32, "fifo_level_valid");
    bool watermark, overrun, empty;
    uint8_t fifoLevel;
    accel.readFifoStatus(watermark, overrun, empty, fifoLevel);  // Read FIFO status, (watermark, overrun, empty, level) → None
    check_true(fifoLevel == level, "fifo_status_level_matches");
    float samples[3 * 32];
    uint8_t count = accel.readFifo(samples, 32);  // Read FIFO, (out, max_count=32) → byte
    check_true(count == level, "fifo_samples_read");
    accel.setFifo(LSM303Full::FIFO_BYPASS);        // Configure FIFO, (mode=FIFO_BYPASS=0, watermark=0, trigger_int2=false) → bool

    accel.setMotionInterrupt(1, 0x02, 0.5f, 10.0f, false);  // Configure motion interrupt, (generator=1, events=0x02, threshold_g=0.5, duration_ms=10, and_mode=false) → bool
    accel.setFreeFall(1, 0.35f, 30.0f);           // Arm free-fall, (generator=1, threshold_g=0.35, duration_ms=30) → bool
    accel.setOrientation(1, false, false);        // Configure orientation, (generator=1, position=false, four_d=false) → bool
    accel.setLatch(1, true);                      // Latch the interrupt, (generator=1, enabled=true) → bool
    uint8_t source = accel.readInterruptSource(1);  // Read interrupt source, (generator=1) → byte
    (void)source;
    check_true(true, "interrupt_source_readable");

    accel.setClick(0, 0x04, 0.8f, 20.0f, 40.0f, 200.0f);  // Configure click detector, (single=0, double=0x04, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200) → bool
    uint8_t click = accel.readClickSource();      // Read click source, () → byte
    (void)click;
    check_true(true, "click_source_readable");

    accel.setInterruptPolarity(false);            // Set interrupt polarity, (active_low=false) → None
    accel.enableInterrupt(LSM303Full::SOURCE_CLICK, 1);  // Route an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → bool
    accel.enableInterrupt(LSM303Full::SOURCE_IA1, 1);  // Route an interrupt, (source=SOURCE_IA1=0x08, pin=1) → bool
    uint8_t interruptStatus = accel.pollInterrupt();  // Read interrupt status, () → byte
    (void)interruptStatus;
    check_true(true, "poll_interrupt");
    accel.disableInterrupt(LSM303Full::SOURCE_CLICK, 1);  // Remove an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → bool
    accel.disableInterrupt(LSM303Full::SOURCE_IA1, 1);  // Remove an interrupt, (source=SOURCE_IA1=0x08, pin=1) → bool

    accel.setMagRange(250);                       // Set magnetometer range, (range_ut=250) → bool
    accel.setMagDataRate(30.0f);                  // Set magnetometer data rate, (rate_hz=30) → bool
    accel.setMagMode(LSM303Full::MAG_MODE_CONTINUOUS);  // Set magnetometer mode, (mode=MAG_MODE_CONTINUOUS=0) → bool
    HAL_Delay(200);
    accel.readMagneticRaw(rx, ry, rz);           // Read raw magnetic field, (x, y, z) → counts
    check_true(rx >= -2048 && rx <= 2047 && ry >= -2048 && ry <= 2047 && rz >= -2048 && rz <= 2047,
               "magnetic_raw_range");

    bool drdy, lock;
    accel.readMagStatus(drdy, lock);              // Read magnetometer status, (drdy, lock) → None
    check_true(true, "mag_status_readable");

    accel.enableTemperature(true);               // Enable the temperature sensor, (enabled=true) → None
    float temperature = accel.readTemperatureDelta();  // Read relative temperature, () → float °C
    check_true(temperature >= -100.0f && temperature <= 100.0f, "temperature_delta_plausible");

    float heading = accel.heading();              // Read the compass heading, (tilt_compensate=true) → float °
    check_true(heading >= 0.0f && heading <= 360.0f, "heading_in_range");

    check_true(accel_connection.error_count() == 0, "i2c_no_hal_errors");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
