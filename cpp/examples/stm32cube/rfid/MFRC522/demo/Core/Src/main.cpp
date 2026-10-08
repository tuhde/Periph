#include <stdio.h>
#include <string.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "SPIConnectionSTM32Cube.h"
#include "MFRC522.h"

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

static const uint8_t CREDITS_BLOCK  = 4;
static const uint32_t INITIAL_CREDITS = 10;

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init(SPI_BAUDRATEPRESCALER_64, SPI_POLARITY_LOW, SPI_PHASE_1EDGE);
    HAL_Delay(2000);

    SPIConnectionSTM32Cube connection(&hspi1, GPIOB, GPIO_PIN_6);
    MFRC522Full mfrc(connection);                                   // Create MFRC522 driver, (connection)

    // --- Prepaid-card credit counter ---
    // Simulates a transit-gate / vending-machine credit system using a MIFARE
    // Classic value block. The factory default key A (FF FF FF FF FF FF) is
    // used for the demo only — replace with a per-deployment secret in any
    // real access-control system.
    mfrc.antenna_on();                                             // Enable antenna driver (TX1+TX2), () → void
    mfrc.set_antenna_gain(38);                                     // Set receiver gain, (dB=18/23/33/38/43/48) → void

    while (1) {
        if (!mfrc.is_card_present()) {                             // Detect card in field, () → bool
            HAL_Delay(200);
            continue;
        }

        // --- Detect a card and select it for authenticated access ---
        uint8_t uid[10];
        size_t uid_len = 0;
        if (!mfrc.select_card(uid, uid_len)) {                     // Anticollision/Select (leaves card active), (out, len) → bool
            HAL_Delay(200);
            continue;
        }

        // --- Authenticate with the well-known MIFARE factory default key A ---
        // In a real deployment this would be a per-card key stored securely
        // (e.g. diversified per card UID and held in an HSM or secure element).
        uint8_t factory_key[6] = {0xFF,0xFF,0xFF,0xFF,0xFF,0xFF};
        if (!mfrc.authenticate(CREDITS_BLOCK, MFRC522Full::KEY_A, factory_key, uid)) { // Run MFAuthent, (block, key_type, key=6 B, uid=4 B) → bool
            printf("authentication failed\r\n");
            mfrc.halt_card();
            HAL_Delay(1000);
            continue;
        }

        // --- Read the current value block; initialise it if unprogrammed ---
        uint8_t block[16];
        if (mfrc.read_block(CREDITS_BLOCK, block)) {               // Read 16-byte block, (block_address, out=16 B) → bool
            bool all_zero = true;
            for (int i = 0; i < 16; i++) if (block[i] != 0) { all_zero = false; break; }
            if (all_zero) {
                uint8_t vb[16] = {};
                uint32_t v = INITIAL_CREDITS;
                vb[0] = v & 0xFF; vb[1] = (v >> 8) & 0xFF;
                vb[2] = (v >> 16) & 0xFF; vb[3] = (v >> 24) & 0xFF;
                vb[4] = (~v) & 0xFF; vb[5] = ((~v) >> 8) & 0xFF;
                vb[6] = ((~v) >> 16) & 0xFF; vb[7] = ((~v) >> 24) & 0xFF;
                vb[8] = vb[0]; vb[9] = vb[1]; vb[10] = vb[2]; vb[11] = vb[3];
                vb[12] = CREDITS_BLOCK; vb[13] = ~CREDITS_BLOCK;
                vb[14] = CREDITS_BLOCK; vb[15] = ~CREDITS_BLOCK;
                mfrc.write_block(CREDITS_BLOCK, vb);               // Write 16 bytes, (block, data=16 B) → bool
                mfrc.restore_value(CREDITS_BLOCK);                 // Restore + Transfer, (block) → bool
                                                                   // normalises the value-block layout
            }
        }

        // --- "Spend" one credit; refuse if balance is zero ---
        if (mfrc.read_block(CREDITS_BLOCK, block)) {               // Read current value, (block, out=16 B) → bool
            uint32_t credits = (uint32_t)block[0] | ((uint32_t)block[1] << 8)
                             | ((uint32_t)block[2] << 16) | ((uint32_t)block[3] << 24);
            if (credits == 0) {
                printf("Access denied — no credits remaining\r\n");
            } else {
                mfrc.decrement_value(CREDITS_BLOCK, 1);            // Decrement + Transfer, (block, delta) → bool
                uint8_t updated[16];
                if (mfrc.read_block(CREDITS_BLOCK, updated)) {     // Read updated value, (block, out=16 B) → bool
                    uint32_t bal = (uint32_t)updated[0] | ((uint32_t)updated[1] << 8)
                                 | ((uint32_t)updated[2] << 16) | ((uint32_t)updated[3] << 24);
                    printf("spent 1 credit — remaining: %lu\r\n", (unsigned long)bal);
                }
            }
        }
        mfrc.stop_crypto();                                        // Clear MFCrypto1On, () → void
        mfrc.halt_card();                                          // Send HLTA, () → void
        HAL_Delay(1000);
    }
}
