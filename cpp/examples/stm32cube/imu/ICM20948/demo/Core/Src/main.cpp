#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "ICM20948.h"

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

    I2CConnectionSTM32Cube connection(&hi2c1, 0x68);
    ICM20948Full imu(connection);     // Create ICM20948 driver, (connection) → void

    HAL_Delay(2000);

    // --- Configure for motion and heading monitoring ---
    // ±4g / ±500dps give headroom for sharper motion; DLPFCFG 3 with a divider of
    // 10 keeps a ~100 Hz sample rate with a ~50 Hz noise bandwidth. The magnetometer
    // runs through the chip's own I²C master, so one connection is enough.
    imu.configure_accel(1);                       // Configure accel range, (full_scale=0) → void
    imu.configure_gyro(1);                        // Configure gyro range, (full_scale=0) → void
    imu.configure_filters(3, 3, true, true);      // Configure low-pass filters, (gyro_dlpf=3, accel_dlpf=3, gyro_filter=true, accel_filter=true) → void
    imu.configure_sample_rate(10, 10);            // Configure sample rate, (gyro_div=10, accel_div=10) → void
    imu.enable_mag();                             // Enable magnetometer, () → bool

    float temp0 = imu.temperature();              // Read die temperature, () → float °C
    printf("ICM-20948 die temperature: %.1f C\r\n", temp0);

    // --- Arm wake-on-motion and route it to the interrupt status ---
    // A 200 mg threshold flags a bump; poll_interrupt() reports it as
    // SOURCE_WAKE_ON_MOTION without needing the INT pin wired.
    imu.set_wake_on_motion(200.0f);               // Arm wake-on-motion, (threshold_mg, enabled=true) → void
    imu.enable_interrupt(ICM20948Full::SOURCE_WAKE_ON_MOTION); // Enable interrupt source, (source) → void

    // --- Stream accelerometer + gyro into the FIFO ---
    // Stream mode overwrites the oldest data; once per second the FIFO is
    // drained and counted in 12-byte frames (accel X, Y, Z, gyro X, Y, Z).
    imu.enable_fifo(true, true, false);           // Enable FIFO sources, (accel=true, gyro=true, temp=false) → void
    imu.set_fifo_mode(false);                     // Select FIFO mode, (snapshot=false) → void

    printf("roll     pitch    heading  |gyro|\r\n");

    for (int iteration = 0; iteration < 600; iteration++) {            // 600 x 100 ms = 60 s
        float a[3], g[3], t;
        imu.read_all(a, g, t);                        // Read one coherent sample, (float[3], float[3], float&) → void m/s², rad/s, °C
        float mx = 0, my = 0, mz = 0;
        imu.mag(mx, my, mz);                          // Read 3-axis magnetic field, (float&, float&, float&) → bool µT

        // --- Compute roll, pitch and a tilt-compensated heading ---
        // The magnetometer frame is rotated 180° about X relative to the inertial
        // frame, so (mx, -my, -mz) maps it into the accel frame before the tilt
        // compensation rotates it into the horizontal plane.
        const float RAD2DEG = 57.2957795f;
        float roll = atan2f(a[1], a[2]);
        float pitch = atan2f(-a[0], sqrtf(a[1] * a[1] + a[2] * a[2]));
        float bx = mx, by = -my, bz = -mz;
        float xh = bx * cosf(pitch) + bz * sinf(pitch);
        float yh = bx * sinf(roll) * sinf(pitch) + by * cosf(roll) - bz * sinf(roll) * cosf(pitch);
        float heading = atan2f(-yh, xh) * RAD2DEG;
        if (heading < 0.0f) heading += 360.0f;
        float gyro_mag = sqrtf(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);

        printf("%.1f      %.1f      %.1f      %.3f\r\n", roll * RAD2DEG, pitch * RAD2DEG, heading, gyro_mag);

        uint8_t status = imu.poll_interrupt();        // Read interrupt status, () → uint8_t
        if (status & ICM20948Full::SOURCE_WAKE_ON_MOTION) {
            printf("MOTION\r\n");
        }

        if (iteration % 10 == 9) {
            uint8_t fifo[240];
            uint16_t n = imu.fifo_count();            // Read FIFO byte count, () → uint16_t
            uint16_t got = imu.read_fifo(fifo, sizeof(fifo)); // Read FIFO data, (uint8_t*, uint16_t) → uint16_t
            printf("FIFO: %d bytes, %d frames\r\n", (int)(got), (int)(got / 12));
            (void)n;
        }
        HAL_Delay(100);
    }
    while (true) HAL_Delay(1000);
}
