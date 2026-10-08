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

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init(SPI_BAUDRATEPRESCALER_16, SPI_POLARITY_LOW, SPI_PHASE_1EDGE);
    HAL_Delay(2000);

    SPIConnectionSTM32Cube connection(&hspi1, GPIOB, GPIO_PIN_6);       // Create SPI connection, (&hspi1, GPIOB, GPIO_PIN_6) → SPIConnectionSTM32Cube
    RFM95Full radio(connection, 868000000);              // Create RFM95W full driver, (connection, frequency_hz=868e6 Hz) → RFM95Full
                                                         // runs the LoRa init sequence: SF7 / 125 kHz / CR 4/5, CRC on, +17 dBm

    radio.reset();                                       // Reset radio registers, () → void
                                                         // re-runs the LoRa init sequence; pulse NRESET low > 100 µs first if it is wired
    uint8_t ver = radio.version();                       // Read silicon version, () → uint8_t
                                                         // expect 0x12 (SX1276); 0x00 or 0xFF points to a wiring or SPI problem
    printf("version=0x%02X\r\n", ver);

    radio.sleep();                                       // Enter SLEEP mode, () → void
                                                         // lowest-power mode; FIFO is not accessible
    radio.standby();                                     // Enter STDBY mode, () → void
                                                         // oscillator running; required before FIFO access or a frequency change
    radio.set_frequency(868100000);                      // Change carrier frequency, (frequency_hz=862e6–1020e6 Hz) → void
                                                         // writes RegFrf (61.035 Hz steps); only allowed in SLEEP or STDBY
    radio.configure(9, 125.0f, 5, true);                 // Configure LoRa modem, (sf=6–12, bandwidth_khz=7.8–500 kHz, coding_rate=5–8, crc=true) → void
                                                         // SF9 / 125 kHz / CR 4/5 with payload CRC; SF6 switches to implicit header
    radio.set_tx_power(14, true);                        // Set TX power, (power_dbm=2–20 dBm, use_pa_boost=true) → void
                                                         // PA_BOOST path at +14 dBm; +20 dBm also enables RegPaDac high-power mode

    const uint8_t msg[] = "hello";
    radio.send(msg, sizeof(msg) - 1);                    // Send packet, (data, len ≤ 255) → void
                                                         // STDBY → fill FIFO → TX → poll TxDone → STDBY

    uint8_t buf[255];
    size_t len = 0;
    if (radio.receive(buf, len, 2000)) {                 // Receive single packet, (buf, len, timeout_ms=2000 ms) → bool
                                                         // RXSINGLE; true on RxDone, false on timeout
        float pkt_rssi = radio.last_packet_rssi();       // Last packet RSSI, () → float dBm
                                                         // RegPktRssiValue − 137
        float pkt_snr = radio.last_packet_snr();         // Last packet SNR, () → float dB
                                                         // signed RegPktSnrValue / 4
        printf("rx %u B  rssi=%.1f dBm  snr=%.1f dB\r\n", (unsigned)len, pkt_rssi, pkt_snr);
    } else {
        printf("rx timeout\r\n");
    }

    radio.receive_continuous();                          // Enter continuous RX, () → void
                                                         // receiver stays on; each packet is buffered in the FIFO until read
    for (int i = 0; i < 100; i++) {                      // listen for about 5 s
        if (radio.read_packet(buf, len)) {               // Read buffered packet, (buf, len) → bool
                                                         // false if no packet has arrived since the last call
            printf("got %u B\r\n", (unsigned)len);
        }
        if (i % 20 == 0) {
            float ch_rssi = radio.rssi();                // Current channel RSSI, () → float dBm
                                                         // live RegRssiValue − 137; only meaningful while receiving
            printf("channel rssi=%.1f dBm\r\n", ch_rssi);
        }
        HAL_Delay(50);
    }
    radio.stop_receive();                                // Leave continuous RX, () → void
                                                         // returns to STDBY

    radio.sleep();                                       // Enter SLEEP mode, () → void
                                                         // park the radio in its lowest-power mode
    while (true) HAL_Delay(1000);
}
