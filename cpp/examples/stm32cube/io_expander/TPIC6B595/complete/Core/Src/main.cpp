#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "SiPoConnectionSTM32Cube.h"
#include "TPIC6B595.h"

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
    gpioInit.Pin       = GPIO_PIN_5 | GPIO_PIN_7;
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

int main() {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init(SPI_BAUDRATEPRESCALER_64, SPI_POLARITY_LOW, SPI_PHASE_1EDGE);
    HAL_Delay(2000);

    SiPoConnectionSTM32Cube connection(&hspi1, GPIOB, GPIO_PIN_6, GPIOC, GPIO_PIN_7, GPIOB, GPIO_PIN_10); // Create SiPo connection, (hspi, rckPort, rckPin, srclrPort, srclrPin, gPort, gPin)
    TPIC6B595Full<SiPoConnectionSTM32Cube> chip(connection, 2);          // Create TPIC6B595 full driver, (connection, num_devices=2)
                                                                          // two cascaded devices — 16 outputs total (DRAIN0..DRAIN15)

    auto p0 = chip.pin(0);                                                // Get pin proxy for DRAIN0 of device 0, (n=0) → IOExpanderPin

    // --- Pin-level control ---
    p0.high();                                                            // Set DRAIN0 ON, () → void
                                                                          // sets shadow[0] bit 0, reverses the cascade, shifts out and pulses RCK
    p0.low();                                                             // Set DRAIN0 OFF, () → void
                                                                          // clears shadow[0] bit 0, retransmits and latches
    p0.toggle();                                                          // Invert shadow bit, () → void

    uint8_t state = p0.read();                                            // Read pin state, () → uint8_t
                                                                          // returns the shadow bit (no bus read — SiPo is write-only)
    p0.write(HIGH);                                                       // Write pin high, (v=HIGH) → void
                                                                          // equivalent to high(); updates shadow, retransmits, latches
    p0.set(true);                                                         // OutputPin set, (high=true) → void
                                                                          // same path as high()/write(HIGH), but matches the OutputPin contract

    // --- Port-level bulk write ---
    chip.write_port(0, 0xAA);                                             // Write device 0 outputs, (port=0, mask=0xAA) → void
                                                                          // sets DRAIN{1,3,5,7} ON, DRAIN{0,2,4,6} OFF; preserves device 1
    chip.write_port(1, 0x55);                                             // Write device 1 outputs, (port=1, mask=0x55) → void

    // --- Bulk fill / off ---
    chip.fill(true);                                                      // Set every output ON, (value=true) → void
                                                                          // fills every shadow byte with 0xFF and retransmits — fast "all on" path
    chip.fill(false);                                                     // Set every output OFF, (value=false) → void
                                                                          // fills every shadow byte with 0x00 and retransmits — fast "all off" path
    chip.off();                                                           // Turn every output off, () → void
                                                                          // shorthand for fill(false); the safe initial state

    // --- Multi-device bulk write ---
    uint8_t bytes_[2] = { 0x01, 0x80 };
    chip.write_all(bytes_, 2);                                            // Write all device bytes, (values=uint8_t*, len=2) → void
                                                                          // updates both shadow bytes and performs one transmit + latch

    // --- Hardware features (Full only) ---
    chip.clear();                                                         // Pulse SRCLR, () → int
                                                                          // clears the shift register only; outputs unaffected until next RCK pulse
    chip.set_output_enable(false);                                        // Force every output off via G, (enabled=false) → int
                                                                          // drives G HIGH, blanking outputs without disturbing the shadow register
    HAL_Delay(100);
    chip.set_output_enable(true);                                         // Re-enable outputs, (enabled=true) → int
                                                                          // drives G LOW; outputs resume from the previously-latched state

    while (true) HAL_Delay(1000);
}
