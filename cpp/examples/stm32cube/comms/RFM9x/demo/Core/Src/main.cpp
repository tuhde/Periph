/* RFM95W demo — two-node round-trip link test.
 *
 * Hardware: two RFM95W modules, one running this program and one running a
 * receive-and-echo loop. Both are configured for 868 MHz, SF=7, BW=125 kHz,
 * CR 4/5 (use RFM96Full at 433 MHz for the low-band RFM96/98 modules).
 *
 * Runs 10 TX/RX iterations: transmits an incrementing 4-byte big-endian
 * counter, then waits up to 1 s for the peer to echo it back. Prints the
 * round-trip time and per-packet RSSI/SNR on success, then reports the
 * total packet loss.
 */
#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "SPIConnectionSTM32Cube.h"
#include "RFM9x.h"

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
// CS is a plain GPIO on PB6 (D10), idle high; SPIConnectionSTM32Cube drives it
// but does not configure it, so it is set up here.
static void spi1_init(uint32_t prescaler, uint32_t polarity, uint32_t phase) {
    __HAL_RCC_SPI1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_5 | GPIO_PIN_6 | GPIO_PIN_7;
    gpioInit.Mode      = GPIO_MODE_AF_PP;
    gpioInit.Pull      = GPIO_NOPULL;
    gpioInit.Speed     = GPIO_SPEED_FREQ_HIGH;
    gpioInit.Alternate = GPIO_AF5_SPI1;
    HAL_GPIO_Init(GPIOA, &gpioInit);

    gpioInit.Pin   = GPIO_PIN_6;
    gpioInit.Mode  = GPIO_MODE_OUTPUT_PP;
    gpioInit.Pull  = GPIO_NOPULL;
    gpioInit.Speed = GPIO_SPEED_FREQ_HIGH;
    HAL_GPIO_WritePin(GPIOB, GPIO_PIN_6, GPIO_PIN_SET);
    HAL_GPIO_Init(GPIOB, &gpioInit);

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

static long long now_ms() {
    return (long long)HAL_GetTick();
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init(SPI_BAUDRATEPRESCALER_16, SPI_POLARITY_LOW, SPI_PHASE_1EDGE);
    HAL_Delay(2000);

    SPIConnectionSTM32Cube connection(&hspi1, GPIOB, GPIO_PIN_6);       // Create SPI connection, (&hspi1, GPIOB, GPIO_PIN_6) → SPIConnectionSTM32Cube
    RFM95Full radio(connection, 868000000);              // Create RFM95W driver, (connection, frequency_hz=868e6 Hz) → RFM95Full

    // --- Configure for a short-range link test ---
    // SF7 / 125 kHz / 4/5 keeps airtime low (about 40 ms for 4 bytes) so the
    // round trip fits in a 1 s window; +17 dBm on PA_BOOST gives plenty of
    // link margin for two modules on the same desk.
    radio.configure(7, 125.0f, 5);                       // Configure LoRa modem, (sf=7, bandwidth_khz=125.0 kHz, coding_rate=5) → void
    radio.set_tx_power(17, true);                        // Set TX power, (power_dbm=17 dBm, use_pa_boost=true) → void

    // --- Ping-pong loop ---
    // Each iteration sends the counter and immediately listens for the echo.
    // A missing or corrupted echo counts as a lost packet; the RSSI and SNR of
    // good echoes show the link quality in the return direction.
    const unsigned total = 10;
    unsigned loss = 0;
    for (unsigned n = 0; n < total; n++) {
        uint8_t tx[4] = { (uint8_t)(n >> 24), (uint8_t)(n >> 16), (uint8_t)(n >> 8), (uint8_t)n };

        long long t0 = now_ms();
        radio.send(tx, 4);                               // Send packet, (data, len=4) → void

        uint8_t rx[255];
        size_t got = 0;
        bool ok = radio.receive(rx, got, 1000);          // Receive single packet, (buf, len, timeout_ms=1000 ms) → bool
        long long t1 = now_ms();

        if (ok && got == 4 && rx[0] == tx[0] && rx[1] == tx[1] && rx[2] == tx[2] && rx[3] == tx[3]) {
            float rssi = radio.last_packet_rssi();       // Last packet RSSI, () → float dBm
            float snr = radio.last_packet_snr();         // Last packet SNR, () → float dB
            printf("[%u] echo rtt=%lld ms  rssi=%.1f dBm  snr=%.1f dB\r\n", n, t1 - t0, rssi, snr);
        } else {
            loss++;
            printf("[%u] no echo\r\n", n);
        }
        HAL_Delay(200);
    }

    // --- Summary ---
    // Packet loss over the run is the headline number for link reliability.
    printf("done: %u/%u successful, %u lost\r\n", total - loss, total, loss);
    while (true) HAL_Delay(1000);
}
