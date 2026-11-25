#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "24AA02UID.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x50);
    EEPROM24AA02UIDFull eeprom(connection);

    uint8_t uid[4];
    eeprom.read_uid(uid);                                       // Read 32-bit unique serial number, (buf[4]) → void
                                                                // reads 4 bytes at 0xFC-0xFF
    printf("UID bytes: ");
    for (uint8_t i = 0; i < 4; i++) {
        if (uid[i] < 0x10) printf("%d", '0');
        printf("0x%X", (unsigned)uid[i]);
    }
    printf("\r\n");

    uint32_t uid_int = ((uint32_t)uid[0] << 24) | ((uint32_t)uid[1] << 16)
                     | ((uint32_t)uid[2] << 8)  |  (uint32_t)uid[3];
    printf("UID int:   ");
    printf("%d\r\n", uid_int);

    uint8_t mfr = eeprom.read_manufacturer_code();             // Read manufacturer code, () → byte
                                                                // reads 0xFA; expect 0x29 (Microchip)
    uint8_t dev = eeprom.read_device_code();                   // Read device code, () → byte
                                                                // reads 0xFB; expect 0x41
    printf("MFR: 0x");
    if (mfr < 0x10) printf("%d", '0');
    printf("0x%X", (unsigned)mfr);
    printf("  DEV: 0x");
    if (dev < 0x10) printf("%d", '0');
    printf("0x%X\r\n", (unsigned)dev);

    uint8_t first = eeprom.read_byte(0x00);                    // Read a single byte, (address=0x00-0x7F) → byte
                                                                // random read at user EEPROM address
    printf("First byte: 0x");
    if (first < 0x10) printf("%d", '0');
    printf("0x%X\r\n", (unsigned)first);

    eeprom.write_byte(0x10, 0xA5);                             // Write a single byte, (address, value) → void
                                                                // byte write + delay until complete (max 5 ms)
    uint8_t verify = eeprom.read_byte(0x10);                   // Read a single byte, (address=0x00-0x7F) → byte
    printf("Wrote 0xA5, read back: 0x");
    if (verify < 0x10) printf("%d", '0');
    printf("0x%X\r\n", (unsigned)verify);

    uint8_t buf[8];
    eeprom.read(0x20, buf, 8);                                 // Sequential read, (address, buf, length) → void
                                                                // reads 8 bytes starting at address
    printf("Block @ 0x20: ");
    for (uint8_t i = 0; i < 8; i++) {
        if (buf[i] < 0x10) printf("%d", '0');
        printf("0x%X", (unsigned)buf[i]);
        printf("%d", ' ');
    }
    printf("\r\n");

    uint8_t page_data[] = { 0x01, 0x02, 0x03, 0x04 };
    eeprom.write_page(0x40, page_data, 4);                     // Page write, (address, data, length) → void
                                                                // writes up to 8 bytes within one page

    uint8_t cross[] = { 0xAA, 0xBB, 0xCC, 0xDD, 0xEE };
    eeprom.write(0x44, cross, 5);                              // Arbitrary-length write, (address, data, length) → void
                                                                // splits at 8-byte page boundaries; waits for each chunk
    printf("Multi-page write complete\r\n");
    while (true) HAL_Delay(1000);
}
