#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "InputPinSTM32Cube.h"
#include "MCP23017.h"

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

// The INT line is wired to PB3 (D3, EXTI3). InputPinSTM32Cube arms the EXTI line in
// onEdge(); the project must route the IRQ to the HAL and the HAL callback back to it.
extern "C" void EXTI3_IRQHandler(void) {
    HAL_GPIO_EXTI_IRQHandler(GPIO_PIN_3);
}

extern "C" void HAL_GPIO_EXTI_Callback(uint16_t gpioPin) {
    InputPinSTM32Cube::dispatchFromISR(gpioPin);
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    InputPinSTM32Cube intPin(GPIOB, GPIO_PIN_3);               // Create INT pin, (port=GPIOB, pin=GPIO_PIN_3 / D3)
    I2CConnectionSTM32Cube connection(&hi2c1, 0x20, &intPin);       // Create I2C connection, (i2c, addr=0x20, intPin)
    MCP23017Full mcp(connection, /*addr=*/0x20);

    mcp.configure_pullup(0, 0x3F);                          // Enable pull-ups on GPA0–GPA5, (port=0, mask=0x3F) → None
    mcp.configure_polarity(0, 0x00);                        // Set normal polarity PORTA, (port=0, mask=0x00) → None

    auto p7 = mcp.pin(7);                                    // GPA7 is output-only; get as output
    p7.mode(OUTPUT);                                        // Set pin direction, (mode=OUTPUT) → None
    p7.low();                                                // Drive GPA7 low, () → None

    for (uint8_t n = 0; n < 8; n++) {
        auto p = mcp.pin(n);                                 // Get pin n as input, (n) → IOExpanderPin
        p.mode(INPUT);                                       // Set pin direction, (mode=INPUT) → None
        uint8_t val = p.read();                              // Read pin level, () → uint8_t 0|1
        printf("GPA");
        printf("%d", n);
        printf("=");
        printf("%d", val);
        printf("  ");
    }
    printf("\r\n");

    mcp.write_port(0, 0x80);                               // Write GPA7 high, (port=0, mask=0x80) → None

    mcp.set_default_value(0, 0x00);                        // Set DEFVAL for PORTA, (port=0, mask=0x00) → None

    mcp.onInterrupt([](uint8_t port, uint8_t status) {      // Subscribe to INT on both ports, (callback, intPin, mirror) → None
        printf("port %d changed: 0x%X\r\n", port, (unsigned)status);
    }, &intPin, /*mirror=*/true);

    mcp.offInterrupt(0);                                    // Unsubscribe PORTA, (port=0) → None

    uint8_t porta = mcp.read_port(0);                       // Read PORTA, (port=0) → uint8_t
    uint8_t portb = mcp.read_port(1);                      // Read PORTB, (port=1) → uint8_t
    printf("PORTA=");
    printf("0x%X", (unsigned)porta);
    printf("  PORTB=");
    printf("0x%X\r\n", (unsigned)portb);
    while (true) HAL_Delay(1000);
}
