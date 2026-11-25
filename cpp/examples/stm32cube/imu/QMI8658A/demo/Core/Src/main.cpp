#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include <math.h>
#include "QMI8658A.h"

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

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();

    I2CConnectionSTM32Cube connection(&hi2c1, 0x6B);
    QMI8658AFull imu(connection);     // Create QMI8658A driver, (connection) → void

    HAL_Delay(2000);

    // --- Identify the part and run the built-in checks ---
    // The revision, firmware version and USID identify the silicon; the
    // self-tests need the board lying still and the gyro calibration trims the
    // X/Y gains before real use.
    uint8_t revision, firmware[3], usid[6];
    imu.device_info(revision, firmware, usid);    // Read device info, (uint8_t&, uint8_t[3], uint8_t[6]) → void
    printf("revision 0x%02X firmware %d.%d.%d\r\n", (int)revision, (int)firmware[0], (int)firmware[1], (int)firmware[2]);
    float st[3];
    bool accel_ok = imu.self_test_accel(st);      // Run accel self-test, (float[3]) → bool m/s²
    printf("accel self-test: %s\r\n", accel_ok ? "PASS" : "FAIL");
    bool gyro_ok = imu.self_test_gyro(st);        // Run gyro self-test, (float[3]) → bool rad/s
    printf("gyro self-test: %s\r\n", gyro_ok ? "PASS" : "FAIL");
    uint8_t cod_status;
    bool cal_ok = imu.calibrate_gyro(&cod_status); // Calibrate gyroscope, (uint8_t*) → bool
    printf("gyro calibration: %s\r\n", cal_ok ? "OK" : "FAILED");

    // --- Configure ±4 g / ±512 dps at 112.1 Hz ---
    // ODR code 6 gives 112.1 Hz for both sensors; mode 3 filters keep the
    // noise bandwidth near 15 Hz.
    imu.configure_accel(1, 6);                    // Configure accelerometer, (full_scale=0, odr=5) → void
    imu.configure_gyro(5, 6);                     // Configure gyroscope, (full_scale=4, odr=5) → void
    imu.configure_filters(true, 3, true, 3);      // Configure low-pass filters, (accel_filter=true, accel_mode=3, gyro_filter=true, gyro_mode=3) → void

    // --- Step counting and tap detection ---
    // The pedometer defaults suit ~50 Hz, so the window parameters are scaled
    // for 112 Hz; the tap engine runs best above 200 Hz and is only a coarse
    // detector at this rate.
    imu.configure_pedometer(112, 200.0f, 100.0f, 450, 45, 10, 0, 1); // Configure pedometer, (sample_count=50, …) → void
    imu.enable_pedometer(true);                   // Enable pedometer, (enabled=true) → void
    imu.configure_tap();                          // Configure tap engine, (priority=0, …) → void
    imu.enable_tap(true);                         // Enable tap engine, (enabled=true) → void

    // --- Stream into the FIFO ---
    // Stream mode with 32 samples and a watermark of 16 is drained once per
    // second; each sample is a 12-byte frame (accel XYZ then gyro XYZ).
    imu.configure_fifo(2, 1, 16);                 // Configure FIFO, (mode=2, size=3, watermark=0) → void
    HAL_Delay(200);

    for (int iteration = 0; iteration < 600; iteration++) {   // 600 x 100 ms = 60 s
        // --- Sample, tilt and step count ---
        // read_all() returns one coherent sample; roll and pitch come from the
        // acceleration vector, the gyro magnitude shows rotation.
        if (imu.data_ready()) {                       // Check for new data, () → bool
            float a[3], g[3], t;
            imu.read_all(a, g, t);                    // Read one coherent sample, (float[3], float[3], float&) → void m/s², rad/s, °C
            float roll = atan2f(a[1], a[2]) * 57.2957795f;
            float pitch = atan2f(-a[0], sqrtf(a[1] * a[1] + a[2] * a[2])) * 57.2957795f;
            float gmag = sqrtf(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
            uint32_t steps = imu.step_count();        // Read step count, () → uint32_t
            printf("roll %.1f pitch %.1f |gyro| %.3f rad/s %.1f C steps %d\r\n", roll, pitch, gmag, t, (int)steps);
        }

        // --- Report taps ---
        // poll_interrupt() flags a tap without the INT pin wired.
        uint16_t status = imu.poll_interrupt();       // Poll interrupt status, () → uint16_t
        if (status & QMI8658AFull::SOURCE_TAP) {
            uint8_t taps, axis; bool negative;
            imu.tap_status(taps, axis, negative);     // Read tap event, (uint8_t&, uint8_t&, bool&) → void
            printf("TAP %s on axis %c\r\n", taps == 2 ? "double" : "single", axis == 1 ? 'X' : (axis == 2 ? 'Y' : (axis == 3 ? 'Z' : '?')));
        }

        // --- Drain the FIFO once per second ---
        if (iteration % 10 == 9) {
            uint8_t fifo[240];
            uint16_t n = imu.fifo_count();            // Read FIFO fill, () → uint16_t bytes
            uint16_t got = imu.read_fifo(fifo, sizeof(fifo)); // Drain the FIFO, (uint8_t*, uint16_t) → uint16_t
            printf("FIFO: %d bytes, %d frames\r\n", (int)n, (int)(got / 12));
        }
        HAL_Delay(100);
    }
    while (true) {
        HAL_Delay(1000);
    }
}
