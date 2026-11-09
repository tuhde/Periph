// TMC5160 Complete example: exercise every method in the public API.
// Wiring: SPI Mode 3 (SCK idles high), 1 MHz; SD_MODE strapped low (ramp generator); DRV_ENN (and DIAG0) on plain GPIO.
#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "SPIConnectionSTM32Cube.h"
#include "OutputPinSTM32Cube.h"
#include "InputPinSTM32Cube.h"
#include "TMC5160.h"

#define LOG(...) printf(__VA_ARGS__)
#define SLEEP_MS(ms) HAL_Delay(ms)

static UART_HandleTypeDef huart2;
static SPI_HandleTypeDef hspi1;

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

// SPI1 on the Arduino header: PA5=SCK (D13), PA6=MISO (D12), PA7=MOSI (D11).
// SPI1 is on APB2 (100 MHz), so the baud rate is 100 MHz / prescaler.
static void spi1_init(uint32_t prescaler, uint32_t polarity, uint32_t phase) {
    __HAL_RCC_SPI1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_5 | GPIO_PIN_6 | GPIO_PIN_7;
    gpioInit.Mode      = GPIO_MODE_AF_PP;
    gpioInit.Pull      = GPIO_NOPULL;
    gpioInit.Speed     = GPIO_SPEED_FREQ_HIGH;
    gpioInit.Alternate = GPIO_AF5_SPI1;
    HAL_GPIO_Init(GPIOA, &gpioInit);

    hspi1.Instance               = SPI1;
    hspi1.Init.Mode              = SPI_MODE_MASTER;
    hspi1.Init.Direction         = SPI_DIRECTION_2LINES;
    hspi1.Init.DataSize          = SPI_DATASIZE_8BIT;
    hspi1.Init.CLKPolarity       = polarity;
    hspi1.Init.CLKPhase          = phase;
    hspi1.Init.NSS               = SPI_NSS_SOFT;
    hspi1.Init.BaudRatePrescaler = prescaler;
    hspi1.Init.FirstBit          = SPI_FIRSTBIT_MSB;
    hspi1.Init.TIMode            = SPI_TIMODE_DISABLE;
    hspi1.Init.CRCCalculation    = SPI_CRCCALCULATION_DISABLE;
    HAL_SPI_Init(&hspi1);
}

static void example(Connection& connection) {
    OutputPinSTM32Cube en(GPIOB, GPIO_PIN_4);
    InputPinSTM32Cube diag(GPIOB, GPIO_PIN_8);
TMC5160Full motor(connection, 0.075f, &en, &diag);    // Create TMC5160 Full driver, (connection, r_sense_ohm=0.075 Ω, en_pin, diag0_pin, diag1_pin)
                                                     // reads IOIN to verify the chip, writes the default configuration
    if (!motor.ok()) { LOG("TMC5160 not found\n"); return; }

    motor.setCurrent(1.0f);                           // Set run current, (rms_a A RMS) → bool
                                                      // programs GLOBALSCALER and IRUN/IHOLD
    motor.setHoldCurrent(0.3f);                       // Set hold current, (rms_a A RMS) → bool
                                                      // independent IHOLD, never above the run current
    motor.setGlobalScaler(0);                         // Set GLOBALSCALER, (value 0 or 32–256) → bool
                                                      // 0 = 256 = full scale
    motor.setCurrentScale(31, 10, 6);                 // Set raw IHOLD_IRUN, (irun 0–31, ihold 0–31, ihold_delay 0–15) → bool
                                                      // bypasses the ampere conversion
    motor.setPowerDownDelay(10);                      // Set TPOWERDOWN, (tpowerdown 0–255) → bool
                                                      // time to standstill current reduction
    motor.setMicrosteps(256);                         // Set microstep resolution, (n 1–256) → bool
                                                      // writes CHOPCONF.MRES; 256 keeps the ramp units in full steps
    motor.setInterpolation(true);                     // Interpolate to 256 microsteps, (enabled) → bool
                                                      // sets CHOPCONF.intpol
    motor.setChopperMode(false);                      // Choose chopper, (spreadcycle) → bool
                                                      // false enables StealthChop below TPWMTHRS
    motor.setStealthchopThreshold(500);               // Set StealthChop velocity limit, (tpwmthrs 20 bit) → bool
                                                      // faster than this the chip switches to SpreadCycle
    motor.setHighVelocityThreshold(0);                // Set THIGH, (thigh 20 bit) → bool
                                                      // 0 keeps the high-velocity features off
    motor.configureChopper(3, 2, 4, 1, 0);            // Configure SpreadCycle timing, (toff, tbl, hstrt, hend, tpfd) → bool
                                                      // direct CHOPCONF fields with chm = 0
    motor.configureConstantOffTime(3, 2, 4, 0, false, 0); // Configure constant off time, (toff, tbl, tfd, offset, disfdcc, tpfd) → bool
                                                      // chm = 1 with fast decay
    motor.configureChopper(3, 2, 4, 1, 0);            // Back to SpreadCycle, (toff, tbl, hstrt, hend, tpfd) → bool
                                                      // chm = 0 again
    motor.configureHighVelocity(false, false);        // Behaviour above THIGH, (vhighchm, vhighfs) → bool
                                                      // both off
    motor.configureStealthchop(true, true, 36, 14, 1, 4, 12); // Configure StealthChop, (autoscale, autograd, pwm_ofs, pwm_grad, pwm_freq, pwm_reg, pwm_lim) → bool
                                                      // PWMCONF fields, freewheel kept
    motor.setFreewheel(0);                            // Set standstill mode, (mode 0–3) → bool
                                                      // PWMCONF.freewheel, only effective when IHOLD is 0
    motor.setDirectionInverted(false);                // Invert direction, (inverted) → bool
                                                      // GCONF.shaft
    motor.setFastStandstill(true);                    // Fast standstill, (enabled) → bool
                                                      // GCONF.faststandstill
    motor.setSmallHysteresis(false);                  // Comparison hysteresis, (enabled) → bool
                                                      // false = 1/16
    motor.setEmergencyStop(false);                    // Emergency stop input, (enabled) → bool
                                                      // GCONF.stop_enable
    motor.setShortProtection(true, true);             // Short protections, (short_gnd, short_supply) → bool
                                                      // true keeps the protection enabled
    motor.configureDiag(false, false, false, false);  // Configure DIAG pins, (diag0_step_output, diag1_dir_output, diag0_pushpull, diag1_pushpull) → bool
                                                      // GCONF bits 7, 8, 12, 13
    motor.configureRamp(0.0f, 800.0f, 400.0f, 400.0f, 400.0f, 400.0f, 800.0f, 10.0f); // Program SixPoint ramp, (vstart, a1, v1, amax, vmax, dmax, d1, vstop) → bool
                                                      // full steps/s and full steps/s²
    motor.setZeroWait(0.1f);                          // Set TZEROWAIT, (seconds s) → bool
                                                      // pause at standstill before reversing
    motor.configureSwitches(false, false, false, false, false, false); // Configure reference switches, (stop_left, stop_right, left_active_low, right_active_low, swap, soft_stop) → bool
                                                      // SW_MODE bits 0–4 and 11
    motor.configureLatch(false, false, false, false, false); // Configure position latch, (left_active, left_inactive, right_active, right_inactive, latch_encoder) → bool
                                                      // SW_MODE bits 5–9
    motor.setStallThreshold(0);                       // Set StallGuard2 threshold, (sgt -64..63) → bool
                                                      // lower = more sensitive
    motor.setStallguardFilter(false);                 // StallGuard2 filter, (enabled) → bool
                                                      // COOLCONF.sfilt
    motor.setCoolstepThreshold(1000);                 // Set CoolStep / stall velocity limit, (tcoolthrs 20 bit) → bool
                                                      // active while TCOOLTHRS >= TSTEP >= THIGH
    motor.configureCoolstep(5, 2, 1, 1, 0);           // Configure CoolStep, (semin 0–15, semax 0–15, seup 0–3, sedn 0–3, seimin 0–1) → bool
                                                      // semin = 0 would disable CoolStep
    motor.enable();                                   // Power stage on, () → void
                                                      // drives DRV_ENN low, waits 150 ms
    motor.setPosition(0.0f);                          // Define position, (position full steps) → bool
                                                      // writes XACTUAL and XTARGET without motion
    motor.moveTo(800.0f);                             // Start positioning move, (position full steps) → bool
                                                      // writes XTARGET; the ramp generator does the rest
    motor.waitUntilReached();                         // Wait for the ramp generator, (timeout_s=0 s, 0 = estimate) → bool
                                                      // polls RAMP_STAT.position_reached
    motor.run(300.0f);                                // Velocity mode, (full_steps_per_s full steps/s) → bool
                                                      // RAMPMODE 1 / 2 by sign
    SLEEP_MS(500);
    motor.stop();                                     // Controlled stop, () → bool
                                                      // velocity mode with VMAX = 0
    motor.moveSteps(100.0f, 200.0f);                  // Blocking relative move, (full_steps, full_steps_per_s=0 full steps/s) → bool
                                                      // returns when the target is reached
    motor.setSpeed(200.0f);                           // Set VMAX, (full_steps_per_s full steps/s) → bool
                                                      // applied immediately in positioning mode
    motor.setAcceleration(400.0f);                    // Set one trapezoid, (full_steps_per_s2 full steps/s²) → bool
                                                      // A1 = AMAX = DMAX = D1
    motor.setRampMode(0);                             // Select ramp mode, (mode 0–3) → bool
                                                      // 0 = positioning

    LOG("position=%d\n", (int)motor.position());      // Read XACTUAL, () → float full steps
                                                      // actual position
    LOG("velocity=%d\n", (int)motor.velocity());      // Read VACTUAL, () → float full steps/s
                                                      // signed actual velocity
    LOG("reached=%d\n", (int)motor.isPositionReached()); // Read position_reached, () → bool
                                                      // RAMP_STAT bit 9
    TMC5160Full::RampStatus rs = motor.readRampStatus(); // Read RAMP_STAT, () → RampStatus
                                                      // decoded ramp status flags
    LOG("vzero=%d\n", (int)rs.vzero);
    motor.clearRampEvents();                          // Clear RAMP_STAT events, () → bool
                                                      // write-1-to-clear bits
    LOG("microsteps=%d\n", (int)motor.microstepPosition()); // Read microstep counter, () → uint16_t 0–1023
                                                      // MSCNT
    TMC5160Full::CoilCurrents cc = motor.readCoilCurrents(); // Read coil currents, () → CoilCurrents {int16_t curA, curB}
                                                      // signed (CUR_A, CUR_B) from MSCURACT
    LOG("currents=%d,%d\n", (int)cc.curA, (int)cc.curB);
    LOG("tstep=%u\n", (unsigned)motor.readTstep());   // Read step interval, () → uint32_t clocks
                                                      // time between 1/256 microsteps
    LOG("scale=%d\n", (int)motor.readCurrentScale()); // Read actual current scale, () → uint8_t 0–31
                                                      // DRV_STATUS.CS_ACTUAL
    LOG("sg_result=%d\n", (int)motor.readStallguard()); // Read StallGuard2 result, () → uint16_t 0–1023
                                                      // SG_RESULT, 0 = highest load
    LOG("stalled=%d\n", (int)motor.isStalled());      // Read stall flag, () → bool
                                                      // DRV_STATUS.StallGuard
    LOG("diag0=%d\n", (int)motor.readDiag(0));        // Read DIAG0 pin, (n 0 or 1) → bool
                                                      // needs the diag pin to be wired
    TMC5160Full::Status st = motor.readStatus();      // Read driver status, () → Status
                                                      // decoded DRV_STATUS
    LOG("stst=%d ot=%d\n", (int)st.stst, (int)st.ot);
    LOG("standstill=%d\n", (int)motor.isStandstill()); // Read standstill flag, () → bool
                                                      // DRV_STATUS.stst
    LOG("fault=%d\n", (int)motor.readFault());        // Read fault bitmask, () → uint8_t
                                                      // FAULT_* constants, 0 = none
    TMC5160Full::GlobalStatus gs = motor.readGlobalStatus(); // Read GSTAT, () → GlobalStatus
                                                      // reset, drv_err, uv_cp
    LOG("reset=%d drv_err=%d uv_cp=%d\n", (int)gs.reset, (int)gs.drvErr, (int)gs.uvCp);
    motor.clearGlobalStatus();                        // Clear GSTAT, () → bool
                                                      // writes 0x07
    TMC5160Full::Ioin io = motor.readIoin();          // Read IOIN, () → Ioin
                                                      // raw word, pin states and silicon version
    LOG("version=0x%02X\n", (unsigned)io.version);
    LOG("gconf=0x%08X\n", (unsigned)motor.readRegister(0x00)); // Read a raw register, (reg) → uint32_t
                                                      // any readable register
    motor.writeRegister(0x13, 500, true);             // Write a raw register, (reg, value, verify=false) → bool
                                                      // here TPWMTHRS again, with the acknowledge check
    motor.setPositionCompare(100.0f);                 // Set X_COMPARE, (position full steps) → bool
                                                      // DIAG1 pulses when XACTUAL matches
    motor.setDcstep(0, 0, 0);                         // Configure DcStep, (vdcmin, dc_time, dc_sg) → bool
                                                      // all zero keeps DcStep off
    motor.setDirectMode(true);                        // Direct coil-current mode, (enabled) → bool
                                                      // GCONF.direct_mode
    motor.setCoilCurrents(100, 0);                    // Write XDIRECT, (coil_a, coil_b -255..255) → bool
                                                      // scaled by IHOLD
    SLEEP_MS(500);
    motor.setDirectMode(false);                       // Back to the ramp generator, (enabled) → bool
                                                      // rewrites XTARGET
    { static const uint32_t table[8] = {1, 2, 3, 4, 5, 6, 7, 8}; if (false) motor.writeMicrostepTable(table, 0, 0x00F70000UL); } // Program the microstep table, (mslut[8], mslutsel, mslutstart) → bool
                                                      // MSLUT0..7, MSLUTSEL, MSLUTSTART; call at MSCNT = 0 (placeholder words, never executed here)
    motor.disable();                                  // Power stage off, () → void
                                                      // drives DRV_ENN high; the motor outputs float
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init(SPI_BAUDRATEPRESCALER_64, SPI_POLARITY_HIGH, SPI_PHASE_2EDGE);  // Mode 3, 100 MHz / 64
    SPIConnectionSTM32Cube connection(&hspi1, GPIOB, GPIO_PIN_6);  // Create SPI connection, (&hspi1, GPIOB, GPIO_PIN_6 = CS) → SPIConnectionSTM32Cube
    example(connection);
    while (true) HAL_Delay(1000);
}
