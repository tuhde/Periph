#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "LTC4151.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x6A);
    LTC4151Full ltc(connection, 0.02f);                                 // Create LTC4151 driver, (connection, r_sense=0.02 Ω) → void
    float f = 0;
    bool ok = false;
    LTC4151Full::Readings r;

    printf("voltage %.3f V\r\n", ltc.voltage());                        // Read supply voltage, () → float V
                                                                        // VIN code × 25 mV
    printf("current %.4f A\r\n", ltc.current());                        // Read load current, () → float A
                                                                        // sense voltage / r_sense
    printf("power %.3f W\r\n", ltc.power());                            // Read load power, () → float W
                                                                        // one block read of SENSE and VIN
    printf("sense voltage %.5f V\r\n", ltc.sense_voltage());            // Read SENSE+ − SENSE−, () → float V
                                                                        // code × 20 µV
    printf("adin %.4f V\r\n", ltc.adin_voltage());                      // Read ADIN pin voltage, () → float V
                                                                        // code × 0.5 mV
    ltc.read_all(r);                                                    // Consistent read of all channels, (out Readings) → void
                                                                        // one block read of registers 0x00–0x05
    printf("vin %.2f V, sense %.5f V, current %.4f A, power %.3f W, adin %.4f V\r\n", r.vin, r.sense_v, r.current, r.power, r.adin);
    printf("raw VIN code %u\r\n", (unsigned)ltc.read_raw(LTC4151Full::VIN));  // Read raw 12-bit code, (channel 0–2) → uint16_t
                                                                        // SENSE = 0, VIN = 1, ADIN = 2

    ltc.set_sense_resistor(0.01f);                                      // Change sense resistor, (ohms) → bool
                                                                        // false (and no change) for ohms <= 0
    printf("sense resistor %.4f ohm\r\n", ltc.get_sense_resistor());    // Read sense resistor, () → float Ω
    ltc.set_sense_resistor(0.02f);                                      // Restore 20 mΩ

    ok = ltc.snapshot(LTC4151Full::SENSE, f);                           // One snapshot conversion, (channel 0–2, out V) → bool
                                                                        // returns to continuous scan afterwards; false on timeout
    printf("snapshot ok %u, sense %.5f V\r\n", (unsigned)ok, f);
    printf("snapshot mode %u\r\n", (unsigned)ltc.is_snapshot_mode());   // Snapshot mode active, () → bool
    printf("VIN busy %u\r\n", (unsigned)ltc.is_busy(LTC4151Full::VIN));  // Channel busy, (channel 0–2) → bool
                                                                        // BUSY bit of the channel low register
    ltc.set_continuous();                                               // Back to continuous scan, () → void

    ltc.set_stuck_bus_timer(true);                                      // Stuck-bus reset timer, (enabled) → void
                                                                        // CONTROL.G2 read-modify-write
    ltc.set_page_rw(true);                                              // I²C page read/write, (enabled) → void
                                                                        // CONTROL.G3 read-modify-write
    printf("control 0x%02X\r\n", (unsigned)ltc.read_control());         // Read CONTROL, () → uint8_t
    ltc.write_control(0x0C);                                            // Write CONTROL, (value) → void
                                                                        // test mode bit 4 is always masked to 0
    printf("temperature %.2f C\r\n", LTC4151Full::temperature_from_adin(900, 1200));  // NTC example circuit, (adin_code, vin_code) → float °C
    printf("done\r\n");

    while (true) HAL_Delay(1000);
}
