#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "MMA8451Q.h"

static I2C_HandleTypeDef hi2c1;
static UART_HandleTypeDef huart2;

extern "C" void SysTick_Handler(void) { HAL_IncTick(); }

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

    I2CConnectionSTM32Cube connection(&hi2c1, 0x1C);
    MMA8451QFull accel(connection);                       // Create MMA8451Q driver, (connection)

    // --- Configure ±4 g, 100 Hz, high-resolution oversampling ---
    // ±4 g gives 2048 counts/g, enough headroom for a 1.0 g tap threshold
    // while still covering a free-fall event. High-resolution oversampling
    // lowers the noise floor, which matters because the orientation detector
    // trips on small changes of the gravity vector.
    accel.set_range(4);                                  // Set measurement range, (range_g=2) → g
                                                          // ±4 g, 2048 counts/g
    accel.set_data_rate(100);                            // Set output data rate, (rate_hz=100) → Hz
                                                          // 100 Hz — 10 ms between samples
    accel.set_oversampling(2, 0);                        // Set oversampling mode, (mode=0, sleep_mode=-1) → None
                                                          // mode 2 = high resolution in WAKE, normal in SLEEP

    bool st_ok = false;
    int16_t dx = 0, dy = 0, dz = 0;
    accel.self_test(st_ok, dx, dy, dz);                  // Run self-test, () → bool, int, int, int
                                                          // electrostatic self-test; prints the per-axis deltas
    printf("self-test: %s dx=%d dy=%d dz=%d\r\n",
           st_ok ? "PASS" : "FAIL", (int)dx, (int)dy, (int)dz);

    accel.set_orientation(true, 100.0f, 45, 4, 4, 1);    // Configure orientation, (enabled, debounce_ms, threshold_deg, hysteresis, z_lock, back_front) → None
                                                          // portrait/landscape at 45°, 100 ms debounce, ±14° hysteresis
    accel.set_pulse(0, 0x04, 0.5f, 0.5f, 1.0f, 40.0f, 100.0f, 300.0f, true, false, true, true);
                                                          // Configure pulse/tap, (single, double, ths_x, ths_y, ths_z, tmlt, latency, window, latch, abort, lpf, hpf) → None
                                                          // double tap on Z, 1.0 g threshold, latched
    accel.set_freefall_motion(false, 7, 0.2f, 60.0f, true, false);
                                                          // Configure freefall/motion, (motion, axes, threshold_g, debounce_ms, latch, debounce_clear) → None
                                                          // freefall below 0.2 g on all axes, 60 ms debounce, latched
    accel.set_auto_sleep(true, 12.5f, 10.0f,
                         MMA8451QFull::SOURCE_PULSE | MMA8451QFull::SOURCE_FF_MT);
                                                          // Configure Auto-SLEEP, (enabled, sleep_rate_hz, timeout_s, wake_sources) → None
                                                          // 12.5 Hz sleep rate, 10 s timeout, wake on tap or freefall

    accel.enable_interrupt(MMA8451QFull::SOURCE_ORIENTATION, 1);
                                                          // Enable interrupt source, (source, pin=1) → None
                                                          // orientation change on INT1
    accel.enable_interrupt(MMA8451QFull::SOURCE_PULSE, 1);  // Enable interrupt source, (source, pin=1) → None
                                                          // tap on INT1
    accel.enable_interrupt(MMA8451QFull::SOURCE_FF_MT, 1);   // Enable interrupt source, (source, pin=1) → None
                                                          // freefall on INT1

    accel.set_fifo(1, 16);                               // Configure the FIFO, (mode=0, watermark=0) → None
                                                          // circular FIFO with a 16-sample watermark

    uint32_t start = HAL_GetTick();
    uint32_t last_print = 0;

    // --- 60-second orientation / tap / free-fall monitor ---
    // The chip sleeps at 12.5 Hz after 10 s of inactivity and wakes on a tap
    // or a free-fall event. Every 250 ms the acceleration and the current
    // system mode are printed; whenever poll_interrupt() reports an event the
    // matching detail is printed too.
    while (HAL_GetTick() - start < 60000) {
        uint32_t now = HAL_GetTick();
        if (now - last_print < 250) {
            HAL_Delay(10);
            continue;
        }
        last_print = now;

        float x, y, z;
        accel.read(x, y, z);                            // Read 3-axis acceleration, (x, y, z) → g, g, g
        uint8_t mode = accel.read_system_mode();        // Read system mode, () → byte
                                                          // SYSMOD bits 1:0: 0 standby, 1 wake, 2 sleep

        printf("%5lu  x=%+.3f  y=%+.3f  z=%+.3f  mode=%s",
               (unsigned long)((now - start) / 1000), (double)x, (double)y, (double)z,
               mode == 0 ? "STANDBY" : (mode == 1 ? "WAKE" : "SLEEP"));

        uint8_t status = accel.poll_interrupt();        // Read interrupt status, () → byte
                                                          // INT_SOURCE; the source flags are cleared by the read
        if (status & MMA8451QFull::SOURCE_ORIENTATION) {
            bool changed = false, lockout = false, back = false;
            uint8_t lapo = 0;
            accel.read_orientation(changed, lockout, lapo, back);
                                                          // Read orientation, (changed, lockout, lapo, back) → bool, bool, byte, bool
            static const char* names[4] = { "portrait up", "portrait down",
                                            "landscape right", "landscape left" };
            printf("  ORIENTATION=%s%s%s", names[lapo & 0x03],
                   back ? " (back)" : "", lockout ? " (lockout)" : "");
        }
        if (status & MMA8451QFull::SOURCE_PULSE) {
            uint8_t pulse = accel.read_pulse_source();  // Read pulse source, () → byte
                                                          // PULSE_SRC; reading it clears the interrupt
            printf("  DOUBLE TAP src=0x%02X", pulse);
        }
        if (status & MMA8451QFull::SOURCE_FF_MT) {
            uint8_t motion = accel.read_motion_source();  // Read motion source, () → byte
                                                              // FF_MT_SRC; reading it clears the latched flags
            printf("  FREE FALL src=0x%02X", motion);
        }

        uint8_t level = accel.fifo_level();             // Read FIFO level, () → byte
                                                          // number of samples buffered in the FIFO
        printf("  fifo=%u\n", level);

        HAL_Delay(10);
    }

    // --- Drain the FIFO and print the mean acceleration ---
    float samples[3 * MMA8451QFull::FIFO_CAPACITY];
    uint8_t got = accel.read_fifo(samples, MMA8451QFull::FIFO_CAPACITY);
                                                              // Drain the FIFO, (out, max_in) → byte
                                                              // up to 32 samples, three floats (x, y, z in g) each
    float mx = 0.0f, my = 0.0f, mz = 0.0f;
    for (uint8_t i = 0; i < got; i++) {
        mx += samples[i * 3 + 0];
        my += samples[i * 3 + 1];
        mz += samples[i * 3 + 2];
    }
    if (got > 0) {
        printf("fifo: %u samples, mean=(%+.3f, %+.3f, %+.3f) g\r\n", got,
               (double)(mx / got), (double)(my / got), (double)(mz / got));
    } else {
        printf("fifo: empty\r\n");
    }
    printf("done\r\n");
    while (true) HAL_Delay(1000);
}
