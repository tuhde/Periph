#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "LSM303.h"

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

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();

    I2CConnectionSTM32Cube accel_connection(&hi2c1, LSM303Minimal::I2C_ADDRESS_ACCEL);
    I2CConnectionSTM32Cube mag_connection(&hi2c1, LSM303Minimal::I2C_ADDRESS_MAG);
    LSM303Full accel(accel_connection, mag_connection);  // Create LSM303 driver, (accel_connection, mag_connection)

    float ax, ay, az;
    float mx, my, mz;
    accel.readAcceleration(ax, ay, az);          // Read acceleration, (x, y, z) → g, g, g
                                                 // burst-reads the six output registers
    accel.readMagnetic(mx, my, mz);                // Read magnetic field, (x, y, z) → uT, uT, uT
                                                 // burst-reads X, Z, Y and reorders them
    printf("accel x=%.4f y=%.4f z=%.4f g\r\n", (double)ax, (double)ay, (double)az);
    printf("mag   x=%.2f y=%.2f z=%.2f uT\r\n", (double)mx, (double)my, (double)mz);

    int16_t rx, ry, rz;
    accel.readAccelerationRaw(rx, ry, rz);       // Read raw acceleration, (x, y, z) → counts
                                                 // signed counts at the current resolution
    printf("raw accel %d %d %d\r\n", (int)rx, (int)ry, (int)rz);

    accel.setRange(4);                           // Set full-scale range, (range_g=4) → bool
                                                 // rewrites FS in CTRL_REG4_A and updates the sensitivity
    accel.setDataRate(50);                       // Set output data rate, (rate_hz=50) → bool
                                                 // rewrites ODR in CTRL_REG1_A
    accel.setMode(LSM303Full::MODE_HIGH_RESOLUTION);  // Set operating mode, (mode=MODE_HIGH_RESOLUTION=2) → bool
                                                 // LPen/HR select low-power, normal or high-resolution
    accel.setAxes(true, true, true);             // Enable axes, (x=true, y=true, z=true) → None
                                                 // rewrites Xen, Yen and Zen
    HAL_Delay(200);

    accel.powerDown();                           // Power down the accelerometer, () → None
                                                 // ODR = 0000, the register contents are retained
    accel.setDataRate(100);                      // Set output data rate, (rate_hz=100) → bool
                                                 // leaves power-down and starts the conversions again

    accel.setHighPass(0, 0, false, true, false, false);  // Configure high-pass filter, (mode=0, cutoff=0, filter_output=false, on_ia1=false, on_ia2=false, on_click=false) → bool
                                                 // rewrites HPM, HPCF, FDS and the per-generator enables
    printf("reference %d\r\n", (int)accel.resetHighPass());  // Reset high-pass filter, () → byte
                                                 // reads REFERENCE_A, which resets the filter
    accel.setReference(0);                       // Set reference value, (value=0) → None
                                                 // the value the high-pass filter compares against

    accel.setFifo(LSM303Full::FIFO_STREAM, 16);  // Configure FIFO, (mode=FIFO_STREAM=2, watermark=0, trigger_int2=false) → bool
                                                 // stream mode with a 16-sample watermark
    printf("fifo level %d\r\n", (int)accel.fifoLevel());  // Read FIFO level, () → byte
                                                 // number of unread sample sets
    bool watermark, overrun, empty;
    uint8_t level;
    accel.readFifoStatus(watermark, overrun, empty, level);  // Read FIFO status, (watermark, overrun, empty, level) → None
                                                 // decodes FIFO_SRC_REG_A
    printf("fifo status wm=%d ov=%d empty=%d level=%d\r\n", (int)watermark, (int)overrun, (int)empty, (int)level);
    float samples[3 * 8];
    uint8_t count = accel.readFifo(samples, 8);  // Read FIFO, (out, max_count=8) → byte
                                                 // pops one sample set per six-byte burst read
    printf("fifo samples %d\r\n", (int)count);

    accel.setMotionInterrupt(1, 0x02, 0.5f, 10.0f, false);  // Configure motion interrupt, (generator=1, events=0x02, threshold_g=0.5, duration_ms=10, and_mode=false) → bool
                                                 // writes INT1_CFG_A, INT1_THS_A and INT1_DURATION_A
    accel.setFreeFall(1, 0.35f, 30.0f);          // Arm free-fall, (generator=1, threshold_g=0.35, duration_ms=30) → bool
                                                 // all three axes low, combined with AND
    accel.setOrientation(1, false, false);       // Configure orientation, (generator=1, position=false, four_d=false) → bool
                                                 // 6D movement recognition on all three axes
    accel.setLatch(1, true);                     // Latch the interrupt, (generator=1, enabled=true) → bool
                                                 // the INT1 line stays asserted until the source is read
    printf("int1 source 0x%02X\r\n", (int)accel.readInterruptSource(1));  // Read interrupt source, (generator=1) → byte
                                                 // reads INT1_SOURCE_A, which clears the latched flag

    accel.setClick(0, 0x04, 0.8f, 20.0f, 40.0f, 200.0f);  // Configure click detector, (single=0, double=0x04, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200) → bool
                                                 // writes CLICK_CFG_A, CLICK_THS_A and the three time registers
    printf("click source 0x%02X\r\n", (int)accel.readClickSource());  // Read click source, () → byte
                                                 // reads CLICK_SRC_A, which clears the latched flag

    accel.setInterruptPolarity(false);           // Set interrupt polarity, (active_low=false) → None
                                                 // H_LACTIVE in CTRL_REG6_A; false keeps the active-high default
    accel.enableInterrupt(LSM303Full::SOURCE_CLICK, 1);  // Route an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → bool
                                                 // only the routing is set up, the generator is configured separately
    accel.disableInterrupt(LSM303Full::SOURCE_CLICK, 1);  // Remove an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → bool
                                                 // clears the routing bit again

    accel.setMagRange(250);                        // Set magnetometer range, (range_ut=250) → bool
                                                 // rewrites GN in CRB_REG_M and updates the gains
    accel.setMagDataRate(30.0f);                   // Set magnetometer data rate, (rate_hz=30) → bool
                                                 // rewrites DO in CRA_REG_M, keeping TEMP_EN
    accel.setMagMode(LSM303Full::MAG_MODE_CONTINUOUS);  // Set magnetometer mode, (mode=MAG_MODE_CONTINUOUS=0) → bool
                                                 // continuous, single or sleep conversion
    accel.readMagneticRaw(rx, ry, rz);            // Read raw magnetic field, (x, y, z) → counts
                                                 // signed counts in X, Y, Z order
    printf("raw mag %d %d %d\r\n", (int)rx, (int)ry, (int)rz);
    bool drdy, lock;
    accel.readMagStatus(drdy, lock);              // Read magnetometer status, (drdy, lock) → None
                                                 // decodes SR_REG_M
    printf("mag drdy=%d lock=%d\r\n", (int)drdy, (int)lock);
    uint8_t identity[3];
    accel.readMagIdentity(identity);              // Read magnetometer identity, (out) → None
                                                 // returns the three identity bytes
    printf("identity 0x%02X 0x%02X 0x%02X\r\n", (int)identity[0], (int)identity[1], (int)identity[2]);
    accel.enableTemperature(true);                // Enable the temperature sensor, (enabled=true) → None
                                                 // sets TEMP_EN in CRA_REG_M
    printf("temperature %.2f C\r\n", (double)accel.readTemperatureDelta());  // Read relative temperature, () → float °C
                                                 // 8 LSB/°C, relative to an unspecified reference

    printf("heading %.1f deg\r\n", (double)accel.heading());  // Read the compass heading, (tilt_compensate=true) → float °
                                                 // tilt-compensated with the accelerometer
    printf("flat heading %.1f deg\r\n", (double)accel.heading(false));  // Read the compass heading, (tilt_compensate=true) → float °
                                                 // the magnetic-only heading

    printf("status 0x%02X\r\n", (int)accel.readStatus());  // Read status register, () → byte
                                                 // raw STATUS_REG_A byte
    printf("new data %d\r\n", (int)accel.newDataAvailable());  // Check new data, () → bool
                                                 // reads STATUS_REG_A.ZYXDA

    accel.reboot();                             // Reboot the accelerometer, () → None
                                                 // reloads the factory trim values and re-runs init()
}
