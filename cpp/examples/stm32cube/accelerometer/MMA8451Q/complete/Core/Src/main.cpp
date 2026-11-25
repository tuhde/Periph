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
    MMA8451QFull accel(connection);                          // Create MMA8451Q Full driver, (connection)

    accel.set_range(4);                                     // Set measurement range, (range_g=2) → g
                                                             // selects ±4 g; sensitivity drops from 4096 to 2048 counts/g
    accel.set_data_rate(100);                               // Set output data rate, (rate_hz=100) → Hz
                                                             // picks the closest supported rate (800…1.56 Hz)
    accel.set_oversampling(2, 0);                           // Set oversampling mode, (mode=0, sleep_mode=-1) → None
                                                             // mode 2 = high resolution; sleep_mode 0 = normal in SLEEP
    accel.set_low_noise(true);                              // Enable low-noise mode, (enabled=False) → None
                                                             // reduced noise, but the range is limited to ±4 g
    accel.set_resolution(14);                              // Set output resolution, (bits=14) → None
                                                             // 14-bit output (two bytes per axis); 8 = fast read
    accel.set_high_pass_output(true, 0);                   // Enable high-pass output, (enabled=False, cutoff=0) → None
                                                             // HPF_OUT=1; SEL=0 selects the lowest cutoff for the ODR
    accel.set_offset(0.01f, -0.02f, 0.0f);                 // Set zero-g offsets, (x_g, y_g, z_g) → g, g, g
                                                             // 2 mg/LSB, clamped to ±0.256 g

    float x, y, z;
    accel.read(x, y, z);                                    // Read 3-axis acceleration, (x, y, z) → g, g, g
                                                             // burst read of 0x01–0x06 at the current range
    int16_t rx, ry, rz;
    accel.read_raw(rx, ry, rz);                            // Read raw counts, (x, y, z) → int, int, int
                                                             // signed 14-bit two's-complement acceleration counts
    bool ready = accel.new_data_available();               // Check new data, () → bool
                                                             // STATUS.ZYXDR; not valid while the FIFO is on
    uint8_t status = accel.read_status();                  // Read STATUS, () → byte
                                                             // raw STATUS register
    uint8_t mode = accel.read_system_mode();               // Read system mode, () → byte
                                                             // SYSMOD bits 1:0: 0 standby, 1 wake, 2 sleep

    float ox, oy, oz;
    accel.get_offset(ox, oy, oz);                          // Read zero-g offsets, (x, y, z) → g, g, g
                                                             // current offset register contents

    accel.set_fifo(1, 16);                                 // Configure the FIFO, (mode=0, watermark=0) → None
                                                             // mode 1 = circular, watermark 16 samples
    accel.set_fifo_trigger(false, false, true, false);     // Select FIFO trigger sources, (transient, orientation, pulse, motion) → None
                                                             // the pulse detector triggers the FIFO
    uint8_t level = accel.fifo_level();                    // Read FIFO level, () → byte
                                                             // number of samples in the FIFO (0–32)
    bool overflow = false, watermark = false;
    uint8_t count = 0;
    accel.read_fifo_status(overflow, watermark, count);    // Read FIFO status, (overflow, watermark, count) → bool, bool, byte
                                                             // decoded F_STATUS; reading it clears SRC_FIFO
    float fifo[3 * 8];
    uint8_t got = accel.read_fifo(fifo, 8);                // Drain the FIFO, (out, max_in) → byte
                                                             // up to 8 samples, three floats (x, y, z in g) each
    accel.set_fifo(0, 0);                                  // Disable the FIFO, (mode=0, watermark=0) → None

    accel.set_freefall_motion(false, 7, 0.2f, 60.0f, true, false);
                                                             // Configure freefall/motion, (motion, axes, threshold_g, debounce_ms, latch, debounce_clear) → None
                                                             // freefall on all axes below 0.2 g, 60 ms debounce, latched
    uint8_t motion = accel.read_motion_source();           // Read motion source, () → byte
                                                             // FF_MT_SRC; reading it clears the latched flags
    accel.set_transient(7, 0.25f, 100.0f, false, false);   // Configure transient, (axes, threshold_g, debounce_ms, bypass_hpf, latch) → None
                                                             // transient on all axes above 0.25 g, 100 ms debounce
    uint8_t transient = accel.read_transient_source();     // Read transient source, () → byte
                                                             // TRANSIENT_SRC; reading it clears the interrupt
    accel.set_pulse(0x04, 0, 0.5f, 0.5f, 1.0f, 40.0f, 100.0f, 300.0f, true, false, true, true);
                                                             // Configure pulse/tap, (single, double, ths_x, ths_y, ths_z, tmlt, latency, window, latch, abort, lpf, hpf) → None
                                                             // single tap on Z at 1.0 g, 40 ms time limit, 100 ms latency
    uint8_t pulse = accel.read_pulse_source();             // Read pulse source, () → byte
                                                             // PULSE_SRC; reading it clears the interrupt

    accel.set_orientation(true, 100.0f, 45, 4, 4, 1);     // Configure orientation, (enabled, debounce_ms, threshold_deg, hysteresis, z_lock, back_front) → None
                                                             // portrait/landscape at 45° with ±14° hysteresis
    bool changed = false, lockout = false, back = false;
    uint8_t lapo = 0;
    accel.read_orientation(changed, lockout, lapo, back); // Read orientation, (changed, lockout, lapo, back) → bool, bool, byte, bool
                                                             // decoded PL_STATUS; 0 portrait up … 3 landscape left

    accel.set_auto_sleep(true, 12.5f, 10.0f, MMA8451QFull::SOURCE_PULSE);
                                                             // Configure Auto-SLEEP, (enabled, sleep_rate_hz, timeout_s, wake_sources) → None
                                                             // 12.5 Hz sleep rate, 10 s timeout, wake on a tap
    accel.set_fifo_gate(false);                            // Set FIFO gate, (enabled=False) → None
                                                             // block FIFO input around sleep/wake transitions
    accel.set_interrupt_pins(true, false);                 // Set interrupt pins, (active_high=False, open_drain=False) → None
                                                             // active-high push-pull interrupts on INT1/INT2

    accel.enable_interrupt(MMA8451QFull::SOURCE_DATA_READY, 1);
                                                             // Enable interrupt source, (source, pin=1) → None
                                                             // route the data-ready event to INT1
    uint8_t intr = accel.poll_interrupt();                 // Read interrupt status, () → byte
                                                             // INT_SOURCE; every source but the data-ready bit is cleared
    accel.disable_interrupt(MMA8451QFull::SOURCE_DATA_READY);
                                                             // Disable interrupt source, (source) → None

    bool st_ok = false;
    int16_t dx = 0, dy = 0, dz = 0;
    accel.self_test(st_ok, dx, dy, dz);                    // Run self-test, () → bool, int, int, int
                                                             // electrostatic self-test; returns the per-axis deltas

    accel.standby();                                       // Enter standby, () → None
                                                             // clears CTRL_REG1.ACTIVE
    accel.activate();                                      // Leave standby, () → None
                                                             // sets CTRL_REG1.ACTIVE and waits the turn-on time
    accel.reset();                                         // Soft reset, () → None
                                                             // CTRL_REG2.RST; the chip returns to its reset values

    (void)ready; (void)status; (void)mode; (void)ox; (void)oy; (void)oz;
    (void)level; (void)overflow; (void)watermark; (void)count; (void)got;
    (void)motion; (void)transient; (void)pulse; (void)changed; (void)lockout;
    (void)lapo; (void)back; (void)intr; (void)st_ok; (void)dx; (void)dy; (void)dz;

    printf("x=%.3f y=%.3f z=%.3f raw=(%d,%d,%d) mode=%u fifo=%u st=%s\r\n",
           (double)x, (double)y, (double)z, (int)rx, (int)ry, (int)rz, mode, got,
           st_ok ? "PASS" : "FAIL");
    while (true) HAL_Delay(1000);
}
