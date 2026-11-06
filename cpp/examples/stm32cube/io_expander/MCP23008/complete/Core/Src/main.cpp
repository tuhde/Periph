#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "MCP23008.h"

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

static MCP23008Full* g_chip = nullptr;

// Callbacks are plain function pointers (no std::function, no heap).
static void onChange(uint8_t status) {
    printf("interrupt, INTF=0x%02X\r\n", status);
}
static void onPin(MCP23008Full::IOExpanderPin*) {
    printf("GP4 falling\r\n");
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x20);

    MCP23008Full chip(connection);                            // Create MCP23008 driver, (connection, addr=0x20)
                                                              // clears OLAT; all pins start as inputs
    g_chip = &chip;

    chip.configure_pullup(0xF0);                              // Enable pull-ups on GP4–GP7, (mask=0xF0) → void
                                                              // writes GPPU; only effective on inputs
    chip.configure_polarity(0x00);                            // Normal input polarity, (mask=0x00) → void
                                                              // writes IPOL; 1 bits invert the GPIO read
    chip.set_default_value(0x00);                             // Set DEFVAL compare value, (mask=0x00) → void
                                                              // reference for default-compare interrupts
    chip.configure_int_pin(false, false);                     // Configure INT output, (openDrain=false, activeHigh=false) → void
                                                              // push-pull, active-low (IOCON ODR/INTPOL)
    chip.set_sequential(true);                                // Keep auto-increment on, (enabled) → void
                                                              // IOCON SEQOP = 0 (driver default)

    for (uint8_t n = 0; n < 4; n++) {
        auto p = chip.pin(n);                                 // Get LED pin proxy, (n) → IOExpanderPin
                                                              // Full proxy with pull-up and watch support
        p.mode(OUTPUT);                                       // Set pin direction, (m INPUT/OUTPUT/INPUT_PULLUP) → void
                                                              // latches OLAT, then clears the IODIR bit
        p.high();                                             // Drive pin high, () → void
                                                              // sets the OLAT bit via the shadow
        p.toggle();                                           // Invert the output latch, () → void
                                                              // one OLAT write, no read-modify-write
    }

    chip.write_port(0, 0x05);                                 // Write the output latch, (port=0, mask) → void
                                                              // whole-port OLAT write, updates the shadow
    uint8_t port_val = chip.read_port();                      // Read all 8 pins, (port=0) → uint8_t bitmask
                                                              // reads GPIO (actual pin levels)
    printf("GPIO=0x%02X\r\n", port_val);
    uint8_t iodir = chip.read_register(0x00);                 // Read raw register, (reg) → uint8_t
                                                              // escape hatch for debugging
    printf("IODIR=0x%02X\r\n", iodir);
    chip.write_register(0x02, 0x10);                          // Write raw register, (reg, value) → void
                                                              // GPINTEN: arm GP4

    chip.enable_pin_interrupt(0xF0);                          // Arm GP4–GP7, (mask) → void
                                                              // writes GPINTEN
    chip.onInterrupt(onChange, nullptr, MCP23x08Full::INT_VS_DEFAULT);// Subscribe to INT, (callback, intPin=nullptr, mode) → bool
                                                              // uses connection.intPin() if wired; otherwise call service()
    chip.pin(4).watch(onPin, InputPin::kFalling);             // Watch GP4 falling edges, (handler, trigger) → bool
                                                              // arms GP4 in GPINTEN, filters the edge in software
    chip.service();                                           // Poll once and dispatch callbacks, () → void
                                                              // reads INTF/INTCAP; use when no INT pin is wired
    uint8_t flags = chip.pollInterrupt();                     // Read INTF and clear, () → uint8_t
                                                              // INTCAP is read and discarded
    uint8_t cap = chip.read_capture();                        // Read captured port state, () → uint8_t
                                                              // port snapshot at the interrupt
    printf("INTF=0x%02X INTCAP=0x%02X\r\n", flags, cap);
    chip.pin(4).unwatch();                                    // Stop watching GP4, () → void
                                                              // clears its GPINTEN bit
    chip.offInterrupt();                                      // Unsubscribe, () → void
                                                              // stops delivery
    chip.reset();                                             // Restore reset values, () → void
                                                              // rewrites all registers, clears shadows
    while (true) HAL_Delay(1000);
}
