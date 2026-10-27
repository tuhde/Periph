#include <stdio.h>
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

// Interrupt callback: receives the poll status byte. Not called in this example (no INT pin wired).
static void onInt(uint8_t status) {
    printf("int status=0x%02X\r\n", status);
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();

    I2CConnectionSTM32Cube accelConn(&hi2c1, 0x19);        // Accelerometer connection at 0x19
    I2CConnectionSTM32Cube magConn(&hi2c1, 0x1E);          // Magnetometer connection at 0x1E

    LSM303Full chip(accelConn, magConn);                    // Create LSM303 full driver, (accel_conn, mag_conn) → sets ±2 g, 100 Hz, ±130 µT, 15 Hz
                                                            // identity check runs here; valid() reports the result
    printf("valid=%d\r\n", chip.valid() ? 1 : 0);

    uint8_t id[3];
    chip.readMagIdentity(id);                               // Read magnetometer identity, (buf) → void
                                                            // copies IRA/IRB/IRC (0x48 0x34 0x33 on a genuine part)

    // --- Accelerometer configuration ---
    chip.setRange(4);                                       // Set accel full scale, (range_g=4) → bool
                                                            // selects ±4 g; updates the cached 2 mg/LSB sensitivity
    chip.setMode(2);                                        // Set accel mode, (mode=2) → bool
                                                            // high-resolution (12-bit): LPen=0, HR=1
    chip.setDataRate(200);                                  // Set accel output rate, (rate_hz=200) → bool
                                                            // 200 Hz is legal in high-resolution mode
    chip.setAxes(true, true, true);                         // Enable axes, (x=true, y=true, z=true) → void
                                                            // Xen, Yen and Zen all set
    chip.setHighPass(0, 0, false, false, false, false);     // Configure high-pass, (mode=0, cutoff=0, filter_output=false, ...) → void
                                                            // filter bypassed on output and all generators
    chip.setReference(0);                                   // Set high-pass reference, (value=0) → void
                                                            // REFERENCE_A value used only in HPM=01
    uint8_t ref = chip.resetHighPass();                     // Reset high-pass filter, () → uint8
                                                            // reading REFERENCE_A resets the filter in HPM=00
    (void)ref;
    chip.setFifo(2, 16, false);                             // Configure FIFO, (mode=2, watermark=16, trigger_int2=false) → bool
                                                            // stream mode, watermark 16 samples, trigger on INT1

    // --- Interrupts ---
    chip.setFreeFall(1, 0.35f, 30.0f);                      // Free-fall on generator 1, (generator=1, threshold_g=0.35, duration_ms=30) → bool
                                                            // all three axes below threshold, AND mode
    chip.setOrientation(2, false, false);                   // 6D movement on generator 2, (generator=2, position=false, four_d=false) → bool
                                                            // reports orientation changes between zones
    chip.setLatch(1, true);                                 // Latch generator 1, (generator=1, enabled=true) → bool
                                                            // holds IA1 until INT1_SOURCE_A is read
    chip.setClick(0x05, 0x02, 0.5f, 10.0f, 20.0f, 50.0f);   // Configure click, (single=0x05, double=0x02, threshold_g=0.5, ...) → void
                                                            // single on X and Z, double on Y
    chip.setInterruptPolarity(false);                       // Set INT polarity, (active_low=false) → void
                                                            // INT1 and INT2 active-high
    chip.enableInterrupt(LSM303Source::IA1, 1);             // Route IA1 to INT1, (source, pin=1) → bool
                                                            // generator 1 event appears on INT1
    chip.enableInterrupt(LSM303Source::CLICK, 1);           // Route click to INT1, (source, pin=1) → bool
                                                            // single/double click appears on INT1
    chip.disableInterrupt(LSM303Source::CLICK, 1);          // Remove click from INT1, (source, pin=1) → bool
                                                            // click no longer routed to INT1
    bool subscribed = chip.onInterrupt(onInt, 1);           // Subscribe to INT1, (callback, pin=1, int_pin=nullptr) → bool
                                                            // returns false here: no InputPin is wired to accelConn
    (void)subscribed;
    chip.offInterrupt(1);                                   // Unsubscribe from INT1, (pin=1) → void
                                                            // removes this driver's handler, if any

    // --- Magnetometer configuration ---
    chip.setMagRange(190);                                  // Set mag full scale, (range_ut=190) → bool
                                                            // ±1.9 gauss; updates the cached gains
    chip.setMagDataRate(30.0f);                             // Set mag output rate, (rate_hz=30) → bool
                                                            // DO=101; TEMP_EN is kept
    chip.setMagMode(0);                                     // Set mag mode, (mode=0) → bool
                                                            // continuous conversion
    chip.enableTemperature(true);                           // Enable temperature, (enabled=true) → void
                                                            // sets TEMP_EN in CRA_REG_M

    // --- Read everything once ---
    HAL_Delay(300);                                         // Let the sensors produce samples (7/ODR and 1/30 Hz)
    float ax, ay, az;
    chip.readAcceleration(ax, ay, az);                      // Read acceleration, (x, y, z) → g, g, g
                                                            // six-byte burst with auto-increment, converted to g
    int16_t rx, ry, rz;
    chip.readAccelerationRaw(rx, ry, rz);                   // Read raw acceleration, (x, y, z) → counts
                                                            // shifted to the current resolution (12-bit in HR)
    bool fresh = chip.newDataAvailable();                   // Check new accel data, () → bool
                                                            // STATUS_REG_A.ZYXDA
    uint8_t st = chip.readStatus();                         // Read accel status, () → uint8
                                                            // raw STATUS_REG_A byte
    bool wm, ov, em;
    uint8_t lvl;
    chip.readFifoStatus(wm, ov, em, lvl);                   // Decode FIFO status, (watermark, overrun, empty, level) → void
                                                            // FIFO_SRC_REG_A fields
    uint8_t fl = chip.fifoLevel();                          // Read FIFO level, () → uint8
                                                            // unread sample sets, 0–32
    float xs[8], ys[8], zs[8];
    uint8_t got = chip.readFifo(xs, ys, zs, 8);             // Drain FIFO, (x, y, z, count=8) → uint8
                                                            // up to 8 sample sets, in g
    uint8_t src1 = chip.readInterruptSource(1);             // Read INT1 source, (generator=1) → uint8
                                                            // reading clears IA1
    uint8_t csrc = chip.readClickSource();                  // Read click source, () → uint8
                                                            // reading clears the click flag
    float mx, my, mz;
    chip.readMagnetic(mx, my, mz);                          // Read magnetic field, (x, y, z) → µT, µT, µT
                                                            // reordered from X, Z, Y on the wire
    int16_t mrx, mry, mrz;
    chip.readMagneticRaw(mrx, mry, mrz);                    // Read raw magnetic field, (x, y, z) → counts
                                                            // signed counts in X, Y, Z order
    bool mdr = chip.magDataReady();                         // Check new mag data, () → bool
                                                            // SR_REG_M.DRDY
    bool drdy, lock;
    chip.readMagStatus(drdy, lock);                         // Decode mag status, (drdy, lock) → void
                                                            // SR_REG_M fields
    float dT = chip.readTemperatureDelta();                 // Read temperature, () → float °C
                                                            // relative delta at 8 LSB/°C, no absolute reference
    float hdg = chip.heading(true);                         // Tilt-compensated heading, (tilt_compensate=true) → deg
                                                            // atan2 of the tilt-corrected field, 0–360
    uint8_t poll = chip.pollInterrupt();                    // Poll interrupt sources, () → uint8
                                                            // combined status of every routed source

    printf("a=%.3f %.3f %.3f g\r\n", (double)ax, (double)ay, (double)az);
    printf("raw=%d %d %d\r\n", rx, ry, rz);
    printf("fresh=%d status=0x%02X fifo_level=%u got=%u\r\n", fresh ? 1 : 0, st, fl, got);
    printf("wm=%d ov=%d em=%d lvl=%u\r\n", wm ? 1 : 0, ov ? 1 : 0, em ? 1 : 0, lvl);
    printf("src1=0x%02X click=0x%02X\r\n", src1, csrc);
    printf("m=%.1f %.1f %.1f uT\r\n", (double)mx, (double)my, (double)mz);
    printf("mraw=%d %d %d\r\n", mrx, mry, mrz);
    printf("mdr=%d drdy=%d lock=%d\r\n", mdr ? 1 : 0, drdy ? 1 : 0, lock ? 1 : 0);
    printf("temp_delta=%.2f C heading=%.1f deg\r\n", (double)dT, (double)hdg);
    printf("poll=0x%02X\r\n", poll);

    // --- Power management and maintenance ---
    chip.setMagMode(2);                                     // Set mag mode, (mode=2) → bool
                                                            // sleep mode to save power
    chip.powerDown();                                       // Power down accelerometer, () → void
                                                            // ODR=0; register contents are kept
    chip.reboot();                                          // Reboot accelerometer memory, () → void
                                                            // BOOT bit, wait 5 ms, then re-run the init sequence

    while (true) HAL_Delay(1000);
}
