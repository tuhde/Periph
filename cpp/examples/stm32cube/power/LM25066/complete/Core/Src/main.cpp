#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "LM25066.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x40);
    LM25066Full hotswap(connection, 1.0f, false);      // Create LM25066 driver, (connection, rSenseMohm, currentLimitHigh)

    printf("%.3f\r\n", (double)hotswap.voltage_in());        // Input voltage, () → float V
    printf("%.3f\r\n", (double)hotswap.voltage_out());       // Output voltage, () → float V
    printf("%.3f\r\n", (double)hotswap.current());           // Input current, () → float A
    printf("%.3f\r\n", (double)hotswap.power());             // Input power, () → float W
    printf("%.2f\r\n", (double)hotswap.temperature());       // MOSFET temperature, () → float °C
    printf("%.3f\r\n", (double)hotswap.voltage_aux());       // VAUX pin voltage, () → float V (0 ... 1.16)
    LM25066Block blk = hotswap.read_block();                 // Time-aligned BLOCK_READ, () → LM25066Block
    printf("0x%04X %.3f A %.3f W\r\n", (unsigned)blk.diagnostic, (double)blk.current, (double)blk.power); // diagnostic / current / voltage_out / voltage_in / power / temperature
    blk = hotswap.read_average();                            // AVG_BLOCK_READ, () → LM25066Block
    blk = hotswap.black_box();                               // Snapshot at first SMBA, () → LM25066Block
    printf("%.3f %.3f\r\n", (double)hotswap.voltage_in_avg(), (double)hotswap.voltage_out_avg()); // Averaged VIN / VOUT, () → float V each
    printf("%.3f %.3f\r\n", (double)hotswap.current_avg(), (double)hotswap.power_avg()); // Averaged IIN / PIN, () → float each
    printf("%.3f\r\n", (double)hotswap.power_peak());        // Peak input power, () → float W
    hotswap.clear_power_peak();                              // Reset the peak register, ()
    hotswap.set_averaging(6);                                // Average 2^6 samples, (exponent 0-12) → bool
    printf("%u\r\n", (unsigned)hotswap.get_averaging());     // Averaging exponent, () → uint8_t
    hotswap.set_vin_uv_warn(10.0f);                          // VIN under-voltage warning, (volts, NAN = off)
    hotswap.set_vin_ov_warn(15.0f);                          // VIN over-voltage warning, (volts, NAN = off)
    hotswap.set_vout_uv_warn(9.0f);                          // VOUT under-voltage warning, (volts, NAN = off)
    hotswap.set_ot_warn(100.0f);                             // Over-temperature warning, (celsius, NAN = off)
    hotswap.set_ot_fault(125.0f);                            // Over-temperature fault, (celsius, NAN = off)
    hotswap.set_iin_warn(20.0f);                             // Input over-current warning, (amps, NAN = off)
    hotswap.set_pin_warn(200.0f);                            // Input over-power warning, (watts, NAN = off)
    LM25066Limits lim = hotswap.get_limits();                // All thresholds, () → LM25066Limits (NAN = disabled)
    printf("%.2f %.2f\r\n", (double)lim.vin_uv_warn, (double)lim.iin_warn); // Read back converted limits
    printf("0x%02X 0x%04X\r\n", (unsigned)hotswap.status_byte(), (unsigned)hotswap.status_word()); // STATUS_BYTE / STATUS_WORD, () → uint8_t / uint16_t
    printf("0x%02X 0x%02X\r\n", (unsigned)hotswap.status_vout(), (unsigned)hotswap.status_input()); // STATUS_VOUT / STATUS_INPUT, () → uint8_t each
    printf("0x%02X 0x%02X 0x%02X\r\n", (unsigned)hotswap.status_temperature(), (unsigned)hotswap.status_cml(), (unsigned)hotswap.status_mfr()); // STATUS_TEMPERATURE / _CML / _MFR_SPECIFIC
    printf("0x%04X\r\n", (unsigned)hotswap.diagnostic_word()); // READ_DIAGNOSTIC_WORD, () → uint16_t
    hotswap.clear_faults();                                  // CLEAR_FAULTS (does not turn the MOSFET on), ()
    printf("%d\r\n", (int)hotswap.is_on());                  // MOSFET on, () → bool
    // hotswap.operation_off();                              // CUTS THE LOAD RAIL, ()
    // hotswap.operation_on();                               // Switch the MOSFET on, ()
    printf("0x%02X\r\n", (unsigned)hotswap.device_setup());  // DEVICE_SETUP, () → uint8_t
    // hotswap.configure_device(7, 0, -1);                   // Override RETRY/CL/CB pins, (retryCode, currentLimitHigh, cbRatioHigh) -1 = pin
    // hotswap.set_gate_mask(0x04);                          // DANGEROUS: disable fault shut-off, (mask bits 5/4/2) → bool
    printf("0x%02X 0x%02X\r\n", (unsigned)hotswap.get_gate_mask(), (unsigned)hotswap.capability()); // GATE_MASK / CAPABILITY, () → uint8_t each
    char id[16], mdl[16], rev[16];
    hotswap.manufacturer_id(id, sizeof id); hotswap.model(mdl, sizeof mdl); hotswap.revision(rev, sizeof rev); // MFR_ID / MFR_MODEL / MFR_REVISION, (buf, len)
    printf("%s %s %s\r\n", id, mdl, rev);
    hotswap.enable_interrupt(LM25066Full::SOURCE_VIN_UV_WARN); // Unmask an alert source, (source)
    hotswap.disable_interrupt(LM25066Full::SOURCE_VIN_UV_WARN); // Mask an alert source, (source)
    printf("0x%04X\r\n", (unsigned)hotswap.pollInterrupt()); // Diagnostic word, () → uint16_t
    hotswap.onInterrupt([](uint16_t diag) { printf("alert 0x%04X\r\n", (unsigned)diag); }); // Subscribe to SMBA, (callback, intPin = nullptr)
    hotswap.offInterrupt();                                  // Unsubscribe, ()
    while (true) HAL_Delay(1000);
}
