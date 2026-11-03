// SSD1683 bring-up probe for NUCLEO-F411RE (throwaway, NOT a driver and NOT a test).
//
// Goal: answer the open questions in specs/display/ssd1683.md on real hardware —
// BUSY polarity and timing, internal-vs-external temperature sensor, 3-colour (0xF7)
// vs B/W (0xFF) update, bit order inside a RAM byte, orientation / data-entry mode,
// and whether the 0x46/0x47 auto-fill works as a solid fill.
//
// Wiring (Waveshare e-Paper Shield, bare panel):
//   SCK=PA5  MOSI=PA7  CS=PB6  D/C=PC7  RES=PA9  BUSY=PA8
// Console: USART2 (ST-LINK VCP) 115200 8N1. Type "help".
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "SPIConnectionSTM32Cube.h"
#include "OutputPinSTM32Cube.h"

static UART_HandleTypeDef huart2;
static SPI_HandleTypeDef hspi1;

static const uint16_t kWidthBytes = 50;   // 400 px / 8
static const uint16_t kHeight     = 300;

extern "C" void SysTick_Handler(void) { HAL_IncTick(); }

extern "C" int _write(int file, char* ptr, int len) {
    (void)file;
    HAL_UART_Transmit(&huart2, reinterpret_cast<uint8_t*>(ptr), static_cast<uint16_t>(len), HAL_MAX_DELAY);
    return len;
}

// --- microsecond timer (DWT cycle counter, 100 MHz) -------------------------------
static void dwt_init(void) {
    CoreDebug->DEMCR |= CoreDebug_DEMCR_TRCENA_Msk;
    DWT->CYCCNT = 0;
    DWT->CTRL |= DWT_CTRL_CYCCNTENA_Msk;
}
// Raw cycle count; differences are wrap-safe for intervals < 2^32 cycles (42.9 s at 100 MHz).
static uint32_t cycles(void) { return DWT->CYCCNT; }
static uint32_t us_since(uint32_t c0) { return (DWT->CYCCNT - c0) / (SystemCoreClock / 1000000U); }

// Busy-wait using the DWT cycle counter (wrap-safe for delays < 42 s).
static void delay_us(uint32_t us) {
    uint32_t c0 = cycles();
    uint32_t target = us * (SystemCoreClock / 1000000U);
    while ((DWT->CYCCNT - c0) < target) { }
}


// --- peripherals ----------------------------------------------------------------
static void uart2_init(void) {
    __HAL_RCC_USART2_CLK_ENABLE();
    GPIO_InitTypeDef g = {};
    g.Pin = GPIO_PIN_2 | GPIO_PIN_3;
    g.Mode = GPIO_MODE_AF_PP;
    g.Pull = GPIO_NOPULL;
    g.Speed = GPIO_SPEED_FREQ_LOW;
    g.Alternate = GPIO_AF7_USART2;
    HAL_GPIO_Init(GPIOA, &g);
    huart2.Instance = USART2;
    huart2.Init.BaudRate = 115200;
    huart2.Init.WordLength = UART_WORDLENGTH_8B;
    huart2.Init.StopBits = UART_STOPBITS_1;
    huart2.Init.Parity = UART_PARITY_NONE;
    huart2.Init.Mode = UART_MODE_TX_RX;
    huart2.Init.HwFlowCtl = UART_HWCONTROL_NONE;
    huart2.Init.OverSampling = UART_OVERSAMPLING_16;
    HAL_UART_Init(&huart2);
}

// SPI1: PA5=SCK, PA7=MOSI (AF5). MISO (PA6) is not used: the panel is write-only.
static void spi1_init(uint32_t prescaler) {
    __HAL_RCC_SPI1_CLK_ENABLE();
    GPIO_InitTypeDef g = {};
    g.Pin = GPIO_PIN_5 | GPIO_PIN_7;
    g.Mode = GPIO_MODE_AF_PP;
    g.Pull = GPIO_NOPULL;
    g.Speed = GPIO_SPEED_FREQ_HIGH;
    g.Alternate = GPIO_AF5_SPI1;
    HAL_GPIO_Init(GPIOA, &g);
    hspi1.Instance = SPI1;
    hspi1.Init.Mode = SPI_MODE_MASTER;
    hspi1.Init.Direction = SPI_DIRECTION_2LINES;
    hspi1.Init.DataSize = SPI_DATASIZE_8BIT;
    hspi1.Init.CLKPolarity = SPI_POLARITY_LOW;      // Mode 0
    hspi1.Init.CLKPhase = SPI_PHASE_1EDGE;
    hspi1.Init.NSS = SPI_NSS_SOFT;
    hspi1.Init.BaudRatePrescaler = prescaler;       // APB2 100 MHz / 128 = 781 kHz (slow, for logic-analyzer bring-up)
    hspi1.Init.FirstBit = SPI_FIRSTBIT_MSB;
    hspi1.Init.TIMode = SPI_TIMODE_DISABLE;
    hspi1.Init.CRCCalculation = SPI_CRCCALCULATION_DISABLE;
    HAL_SPI_Init(&hspi1);
}

static void busy_pin_init(void) {
    GPIO_InitTypeDef g = {};
    g.Pin = GPIO_PIN_8;
    g.Mode = GPIO_MODE_INPUT;
    g.Pull = GPIO_NOPULL;
    HAL_GPIO_Init(GPIOA, &g);
}
static bool busy_level(void) { return HAL_GPIO_ReadPin(GPIOA, GPIO_PIN_8) == GPIO_PIN_SET; }

// --- panel state ----------------------------------------------------------------
static SPIConnectionSTM32Cube* spi = nullptr;
static OutputPinSTM32Cube* dc = nullptr;
static OutputPinSTM32Cube* res = nullptr;

static bool    opt_internal_temp = true;   // send 0x18 0x80 during init
static uint8_t opt_entry_mode    = 0x01;   // 0x11 parameter: 0x01 = X+/Y- (reference flow), 0x03 = X+/Y+ (POR)
static uint8_t opt_update_mode   = 0xF7;   // 0x22 parameter: 0xF7 3-colour, 0xFF B/W

// --- low-level writes ---------------------------------------------------------------
// CS handling is done here, not by SPIConnectionSTM32Cube::write(): that call asserts and releases
// CS around every write, which would split a command from its parameters. The datasheet (Figure
// 6-1) keeps CS# low across the command byte and all its parameter/data bytes, with only D/C
// changing. begin()/end() bracket one such transaction; outside one, every write toggles CS
// (the old behaviour, selectable with "cs toggle").
static bool opt_cs_hold = true;
static bool in_txn = false;

static void cs_low(void)  { HAL_GPIO_WritePin(GPIOB, GPIO_PIN_6, GPIO_PIN_RESET); }
static void cs_high(void) { HAL_GPIO_WritePin(GPIOB, GPIO_PIN_6, GPIO_PIN_SET); }

static void begin(void) { if (opt_cs_hold) { cs_low(); in_txn = true; } }
static void end(void)   { if (in_txn) { cs_high(); in_txn = false; } }

// --- 3-wire SPI (BS1 high): 9-bit frames {D/C, D7..D0}, bit-banged on SCK=PA5 / MOSI=PA7 ---------
// In this mode the chip's D/C# pin must be tied LOW, so the D/C GPIO is held low and the D/C
// state is carried in the 9th (first) bit of each frame instead.
static bool opt_wire3 = false;
static bool dc_state = false;

static void bb_gpio(bool bitbang) {
    GPIO_InitTypeDef g = {};
    g.Pin = GPIO_PIN_5 | GPIO_PIN_7;
    g.Pull = GPIO_NOPULL;
    g.Speed = GPIO_SPEED_FREQ_HIGH;
    if (bitbang) {
        g.Mode = GPIO_MODE_OUTPUT_PP;
        HAL_GPIO_Init(GPIOA, &g);
        HAL_GPIO_WritePin(GPIOA, GPIO_PIN_5, GPIO_PIN_RESET);   // SCK idle low (Mode 0)
    } else {
        g.Mode = GPIO_MODE_AF_PP;
        g.Alternate = GPIO_AF5_SPI1;
        HAL_GPIO_Init(GPIOA, &g);
    }
}

static void bb_frame9(bool dcbit, uint8_t v) {
    for (int i = 8; i >= 0; --i) {
        bool bit = (i == 8) ? dcbit : ((v >> i) & 1);
        HAL_GPIO_WritePin(GPIOA, GPIO_PIN_7, bit ? GPIO_PIN_SET : GPIO_PIN_RESET);
        delay_us(2);
        HAL_GPIO_WritePin(GPIOA, GPIO_PIN_5, GPIO_PIN_SET);      // rising edge samples MOSI
        delay_us(2);
        HAL_GPIO_WritePin(GPIOA, GPIO_PIN_5, GPIO_PIN_RESET);
    }
}

static void set_dc(bool high) {
    dc_state = high;
    dc->set(opt_wire3 ? false : high);
}

static void spi_tx(const uint8_t* d, size_t n) {
    if (!in_txn) cs_low();
    if (opt_wire3) {
        for (size_t i = 0; i < n; ++i) bb_frame9(dc_state, d[i]);
    } else {
        HAL_SPI_Transmit(&hspi1, const_cast<uint8_t*>(d), static_cast<uint16_t>(n), 1000);
    }
    if (!in_txn) cs_high();
}
static void cmd(uint8_t c) {
    set_dc(false);
    spi_tx(&c, 1);
}
static void data(const uint8_t* d, size_t n) {
    set_dc(true);
    spi_tx(d, n);
}
static void data1(uint8_t b) { data(&b, 1); }
// One command with its parameters in a single CS# window.
static void cmd_data(uint8_t c, const uint8_t* d, size_t n) {
    begin();
    cmd(c);
    if (n) data(d, n);
    end();
}

// Wait for BUSY to rise (within 20 ms) and fall; print what was observed. Returns busy time in ms
// or -1 if BUSY never rose, -2 on timeout.
static int32_t wait_busy(const char* label, uint32_t timeout_ms) {
    uint32_t t0 = cycles();
    uint32_t tick0 = HAL_GetTick();    // timeout uses the ms tick: the cycle counter wraps at 42.9 s
    uint32_t t_rise = 0, t_fall = 0;
    bool rose = false;
    bool initially_high = busy_level();
    if (initially_high) { rose = true; t_rise = 0; }
    while (true) {
        uint32_t now = us_since(t0);
        bool b = busy_level();
        if (!rose) {
            if (b) { rose = true; t_rise = now; }
            else if (now > 20000U) {
                printf("  BUSY[%s]: never rose within 20 ms (level low)\r\n", label);
                return -1;
            }
        } else if (!b) {
            t_fall = now;
            break;
        }
        if (HAL_GetTick() - tick0 > timeout_ms) {
            printf("  BUSY[%s]: TIMEOUT after %lu ms (level %s)\r\n", label, (unsigned long)timeout_ms,
                   b ? "HIGH" : "low");
            return -2;
        }
    }
    printf("  BUSY[%s]: %srose +%lu us, fell +%lu us -> busy %lu us (%lu ms)\r\n", label,
           initially_high ? "(already high) " : "", (unsigned long)t_rise, (unsigned long)t_fall,
           (unsigned long)(t_fall - t_rise), (unsigned long)((t_fall - t_rise) / 1000U));
    return static_cast<int32_t>((t_fall - t_rise) / 1000U);
}

// Log every BUSY transition for `ms` milliseconds (polarity-agnostic).
static void watch(uint32_t ms) {
    uint32_t t0 = HAL_GetTick();
    bool last = busy_level();
    printf("  watch %lu ms: BUSY starts %s", (unsigned long)ms, last ? "HIGH" : "low");
    int edges = 0;
    while (HAL_GetTick() - t0 < ms) {
        bool b = busy_level();
        if (b != last) {
            last = b;
            if (edges++ < 40) printf(" | %s@%lu ms", b ? "HIGH" : "low", (unsigned long)(HAL_GetTick() - t0));
        }
    }
    printf(" | end %s (%d edge%s)\r\n", last ? "HIGH" : "low", edges, edges == 1 ? "" : "s");
}

// --- sequences ------------------------------------------------------------------
static void do_status(void) {
    printf("BUSY level: %s | internal_temp=%d entry=0x%02X update=0x%02X\r\n",
           busy_level() ? "HIGH" : "low", opt_internal_temp, opt_entry_mode, opt_update_mode);
}

static void do_reset(void) {
    printf("hardware reset...\r\n");
    printf("  BUSY before reset: %s\r\n", busy_level() ? "HIGH" : "low");
    res->set(false);
    HAL_Delay(10);
    res->set(true);
    HAL_Delay(1);
    printf("  BUSY 1 ms after RES release: %s\r\n", busy_level() ? "HIGH" : "low");
    wait_busy("hw reset", 2000);
    printf("software reset (0x12)...\r\n");
    cmd(0x12);
    wait_busy("sw reset", 2000);
    HAL_Delay(10);
}

static void set_window_and_counters(void) {
    const uint16_t yLast = kHeight - 1;
    uint8_t xwin[2] = { 0x00, static_cast<uint8_t>(kWidthBytes - 1) };
    cmd_data(0x44, xwin, 2);
    uint8_t ywin[4];
    if (opt_entry_mode == 0x01) {   // Y decrement: window from bottom row up to row 0
        ywin[0] = yLast & 0xFF; ywin[1] = yLast >> 8; ywin[2] = 0; ywin[3] = 0;
    } else {                        // Y increment: row 0 .. 299
        ywin[0] = 0; ywin[1] = 0; ywin[2] = yLast & 0xFF; ywin[3] = yLast >> 8;
    }
    cmd_data(0x45, ywin, 4);
}

static void set_counters(void) {
    const uint16_t yLast = kHeight - 1;
    uint8_t x = 0x00;
    cmd_data(0x4E, &x, 1);
    uint8_t y[2];
    if (opt_entry_mode == 0x01) { y[0] = yLast & 0xFF; y[1] = yLast >> 8; }
    else                         { y[0] = 0; y[1] = 0; }
    cmd_data(0x4F, y, 2);
}

static void do_init(void) {
    printf("init: temp sensor %s, entry mode 0x%02X\r\n", opt_internal_temp ? "internal (0x18 0x80)" : "POR (external)",
           opt_entry_mode);
    if (opt_internal_temp) { uint8_t v = 0x80; cmd_data(0x18, &v, 1); }
    const uint16_t gates = kHeight - 1;
    uint8_t drv[3] = { static_cast<uint8_t>(gates & 0xFF), static_cast<uint8_t>(gates >> 8), 0x00 };
    cmd_data(0x01, drv, 3);
    uint8_t em = opt_entry_mode;
    cmd_data(0x11, &em, 1);
    set_window_and_counters();
    uint8_t border = 0x01;
    cmd_data(0x3C, &border, 1);
    uint8_t soft[4] = { 0x8B, 0x9C, 0x96, 0x0F };
    cmd_data(0x0C, soft, 4);
    printf("  init commands sent\r\n");
}

static void do_fill(void) {
    printf("auto-fill: B/W RAM all white via 0x47 0xE6, red RAM all 0 via 0x46 0x66\r\n");
    set_counters();
    uint8_t v = 0xE6;
    cmd_data(0x47, &v, 1);
    wait_busy("0x47 fill", 5000);
    v = 0x66;
    cmd_data(0x46, &v, 1);
    wait_busy("0x46 fill", 5000);
    // Read back is not possible (no MISO); verify visually with the 'update' command.
}

// Test pattern in framebuffer coordinates (row 0 is sent first). Designed so that one update
// answers orientation, bit order and colour questions at a glance.
static uint8_t pattern_bw(uint16_t row, uint16_t col) {
    uint8_t v = 0xFF;                                   // white background
    if (row < 10) v = 0x00;                             // top bar, full width (black)
    if (col == 0 && row < 100) v = 0x00;                // left bar, 8 px wide, rows 0..99
    if (row >= 20 && row < 60 && col >= 2 && col < 7) v = 0x00;   // red square: BW must be 0
    if (row >= 120 && row < 140) v = (col < 4) ? 0xC0 : 0x00;     // bit-order comb (see below)
    if (row >= kHeight - 10) v = 0x00;                  // bottom bar
    if (col == kWidthBytes - 1 && row >= 150) v = 0x00; // right bar, 8 px wide, rows 150..299
    return v;
}
static uint8_t pattern_red(uint16_t row, uint16_t col) {
    return (row >= 20 && row < 60 && col >= 2 && col < 7) ? 0xFF : 0x00;
}

static void stream_plane(uint8_t command, uint8_t (*gen)(uint16_t, uint16_t)) {
    set_counters();
    begin();                                  // CS# low from the command through the last data byte
    cmd(command);
    uint8_t line[kWidthBytes];
    for (uint16_t row = 0; row < kHeight; ++row) {
        for (uint16_t col = 0; col < kWidthBytes; ++col) line[col] = gen(row, col);
        data(line, kWidthBytes);
    }
    end();
}

static void do_pattern(void) {
    printf("pattern: streaming 300 rows into B/W (0x24) and red (0x26) RAM...\r\n");
    uint32_t t0 = cycles();
    stream_plane(0x24, pattern_bw);
    stream_plane(0x26, pattern_red);
    printf("  done in %lu ms\r\n", (unsigned long)(us_since(t0) / 1000U));
    printf("  expect: top-left L (black top bar + left bar), red square inside it, comb band\r\n"
           "  at rows 120-139 (white 2-px lines at x=0,8,16,24 if MSB-leftmost, at x=6,14,22,30 if\r\n"
           "  LSB-leftmost), black bottom bar + right bar.\r\n");
}

static void do_update(void) {
    printf("update: 0x22 0x%02X, 0x20\r\n", opt_update_mode);
    uint32_t t0 = cycles();
    cmd_data(0x22, &opt_update_mode, 1);
    cmd(0x20);
    int32_t r = wait_busy("update", 60000);
    printf("  update total %lu ms (BUSY result %ld)\r\n", (unsigned long)(us_since(t0) / 1000U), (long)r);
}

static void do_sleep(void) {
    printf("deep sleep 1 (0x10 0x01)\r\n");
    uint8_t v = 0x01;
    cmd_data(0x10, &v, 1);
    HAL_Delay(5);
    printf("  BUSY level after sleep command: %s\r\n", busy_level() ? "HIGH" : "low");
}

static void do_raw(char* args) {
    uint8_t buf[64];
    size_t n = 0;
    for (char* tok = strtok(args, " "); tok && n < sizeof(buf); tok = strtok(nullptr, " "))
        buf[n++] = static_cast<uint8_t>(strtoul(tok, nullptr, 16));
    if (n == 0) { printf("usage: c <cmd> [data...] (hex)\r\n"); return; }
    begin();
    cmd(buf[0]);
    if (n > 1) data(buf + 1, n - 1);
    end();
    printf("sent cmd 0x%02X + %u data byte(s)\r\n", buf[0], (unsigned)(n - 1));
    watch(600);
}

// RES# pulse only (no software reset), then log BUSY edges.
static void do_rst(uint32_t low_ms) {
    printf("RES# low for %lu ms, then high\r\n", (unsigned long)low_ms);
    printf("  BUSY before: %s\r\n", busy_level() ? "HIGH" : "low");
    res->set(false);
    HAL_Delay(low_ms);
    printf("  BUSY while RES# low: %s\r\n", busy_level() ? "HIGH" : "low");
    res->set(true);
    watch(1500);
}

// Change the BUSY (PA8) input pull and sample the level: a pin that is really driven by the
// panel ignores the pull; a floating pin follows it.
static void do_pull(const char* which) {
    GPIO_InitTypeDef g = {};
    g.Pin = GPIO_PIN_8;
    g.Mode = GPIO_MODE_INPUT;
    g.Pull = (which[0] == 'u') ? GPIO_PULLUP : (which[0] == 'd') ? GPIO_PULLDOWN : GPIO_NOPULL;
    HAL_GPIO_Init(GPIOA, &g);
    HAL_Delay(20);
    uint32_t high = 0, n = 20000;
    for (uint32_t i = 0; i < n; ++i) high += busy_level() ? 1 : 0;
    printf("BUSY pull=%s: HIGH in %lu of %lu samples\r\n",
           which[0] == 'u' ? "up" : which[0] == 'd' ? "down" : "none", (unsigned long)high, (unsigned long)n);
}

// Output pins reachable by name for logic-analyzer checks.
struct NamedPin { const char* name; const char* label; GPIO_TypeDef* port; uint16_t pin; };
static const NamedPin kPins[] = {
    { "res", "RES# (PA9)", GPIOA, GPIO_PIN_9 },
    { "dc",  "D/C  (PC7)", GPIOC, GPIO_PIN_7 },
    { "cs",  "CS   (PB6)", GPIOB, GPIO_PIN_6 },
};
static const NamedPin* find_pin(const char* name) {
    for (const NamedPin& p : kPins) if (!strcmp(p.name, name)) return &p;
    return nullptr;
}
static void drive(const NamedPin* p, bool high) {
    HAL_GPIO_WritePin(p->port, p->pin, high ? GPIO_PIN_SET : GPIO_PIN_RESET);
}

// Endless square wave on a named output pin for logic-analyzer checks: 100 ms HIGH, 100 ms LOW.
// Runs until any byte arrives on the console (then leaves the pin HIGH).
// NOTE: LOW periods on RES# reset the panel controller.
static void do_pat(const NamedPin* p) {
    printf("%s: endless 100 ms HIGH / 100 ms LOW, send any byte to stop\r\n", p->label);
    while (true) {
        drive(p, true);
        for (int i = 0; i < 100; ++i) {
            HAL_Delay(1);
            if (__HAL_UART_GET_FLAG(&huart2, UART_FLAG_RXNE)) goto stop;
        }
        drive(p, false);
        for (int i = 0; i < 100; ++i) {
            HAL_Delay(1);
            if (__HAL_UART_GET_FLAG(&huart2, UART_FLAG_RXNE)) goto stop;
        }
    }
stop:
    (void)huart2.Instance->DR;          // discard the byte that stopped it
    drive(p, true);
    printf("stopped, %s left HIGH\r\n", p->label);
}

// Hold a named pin at a static level (for continuity / voltage checks).
static void do_hold(const NamedPin* p, bool high) {
    drive(p, high);
    printf("%s held %s\r\n", p->label, high ? "HIGH" : "LOW");
}

static void do_help(void) {
    printf("commands:\r\n"
           "  status            BUSY level + options\r\n"
           "  reset             hardware + software reset with BUSY timing\r\n"
           "  rst [ms]          RES# pulse only (default 10 ms), then log BUSY edges\r\n"
           "  pat res|dc|cs     endless 100 ms HIGH / 100 ms LOW on a pin (any byte stops it)\r\n"
           "  hold res|dc|cs high|low   drive a static level\r\n"
           "  pull up|down|none BUSY pin pull + level sample (is the pin driven or floating?)\r\n"
           "  init              send init sequence (honours temp/entry options)\r\n"
           "  fill              auto-fill RAM (0x47/0x46) and time BUSY\r\n"
           "  pattern           stream the test pattern into both RAM planes\r\n"
           "  update            0x22 <mode>, 0x20; time BUSY\r\n"
           "  sleep             deep sleep 1\r\n"
           "  auto              reset, init, pattern, update\r\n"
           "  temp int|ext      send 0x18 0x80 in init (int) or leave POR (ext)\r\n"
           "  wire3 on|off      send 9-bit frames {D/C,D7..D0} bit-banged (BS1=high / 3-line SPI test)\r\n"
           "  cs hold|toggle    hold CS# low across command+parameters (default) or toggle per write\r\n"
           "  entry 1|3         0x11 = 0x01 (X+/Y-) or 0x03 (X+/Y+)\r\n"
           "  mode color|bw     update mode 0xF7 / 0xFF\r\n"
           "  mode <hex>        arbitrary 0x22 parameter\r\n"
           "  watch [ms]        log BUSY edges (default 1000 ms)\r\n"
           "  c <hex bytes>     raw command + data, e.g. c 22 c7 (logs BUSY edges 600 ms)\r\n");
}

// Blocking line reader on USART2 with echo and backspace.
static void read_line(char* buf, size_t max) {
    size_t n = 0;
    printf("> ");
    while (true) {
        uint8_t ch;
        if (HAL_UART_Receive(&huart2, &ch, 1, HAL_MAX_DELAY) != HAL_OK) continue;
        if (ch == '\r' || ch == '\n') {
            if (n == 0) continue;   // ignore the LF of CRLF
            break;
        }
        if ((ch == 0x08 || ch == 0x7F) && n > 0) { n--; printf("\b \b"); continue; }
        if (ch >= 0x20 && n + 1 < max) { buf[n++] = static_cast<char>(ch); printf("%c", ch); }
    }
    buf[n] = '\0';
    printf("\r\n");
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    __HAL_RCC_GPIOA_CLK_ENABLE();
    __HAL_RCC_GPIOB_CLK_ENABLE();
    __HAL_RCC_GPIOC_CLK_ENABLE();
    dwt_init();
    uart2_init();
    setvbuf(stdout, nullptr, _IONBF, 0);   // the "> " prompt has no newline; don't hold it in the buffer
    spi1_init(SPI_BAUDRATEPRESCALER_128);
    busy_pin_init();

    static SPIConnectionSTM32Cube connection(&hspi1, GPIOB, GPIO_PIN_6, 0, 0);   // CS=PB6, no read/multi-byte bits
    static OutputPinSTM32Cube dcPin(GPIOC, GPIO_PIN_7);                           // D/C=PC7
    static OutputPinSTM32Cube resPin(GPIOA, GPIO_PIN_9);                          // RES=PA9
    spi = &connection;
    dc = &dcPin;
    res = &resPin;
    res->set(true);
    dc->set(false);

    HAL_Delay(200);
    printf("\r\nSSD1683 probe (NUCLEO-F411RE, SPI1 781 kHz Mode 0)\r\n");
    do_status();

    // Run the full sequence on every reset so a capture can be re-triggered with the reset button.
    HAL_Delay(500);
    printf("autorun: reset, init, pattern, update\r\n");
    do_reset();
    do_init();
    do_pattern();
    do_update();
    printf("autorun done\r\n");
    do_help();

    char line[96];
    while (true) {
        read_line(line, sizeof(line));
        char* arg = strchr(line, ' ');
        if (arg) { *arg++ = '\0'; while (*arg == ' ') arg++; }
        if (!strcmp(line, "help")) do_help();
        else if (!strcmp(line, "status")) do_status();
        else if (!strcmp(line, "reset")) do_reset();
        else if (!strcmp(line, "rst")) do_rst(arg ? static_cast<uint32_t>(atoi(arg)) : 10U);
        else if (!strcmp(line, "pat")) {
            char* rest = arg ? strchr(arg, ' ') : nullptr;
            if (rest) *rest++ = '\0';
            (void)rest;
            const NamedPin* p = arg ? find_pin(arg) : nullptr;
            if (!p) printf("usage: pat res|dc|cs\r\n");
            else do_pat(p);
        }
        else if (!strcmp(line, "hold")) {
            char* rest = arg ? strchr(arg, ' ') : nullptr;
            if (rest) *rest++ = '\0';
            const NamedPin* p = arg ? find_pin(arg) : nullptr;
            if (!p || !rest) printf("usage: hold res|dc|cs high|low\r\n");
            else do_hold(p, rest[0] == 'h');
        }
        else if (!strcmp(line, "pull") && arg) do_pull(arg);
        else if (!strcmp(line, "init")) do_init();
        else if (!strcmp(line, "fill")) do_fill();
        else if (!strcmp(line, "pattern")) do_pattern();
        else if (!strcmp(line, "update")) do_update();
        else if (!strcmp(line, "sleep")) do_sleep();
        else if (!strcmp(line, "auto")) { do_reset(); do_init(); do_pattern(); do_update(); }
        else if (!strcmp(line, "temp") && arg) { opt_internal_temp = (arg[0] == 'i'); do_status(); }
        else if (!strcmp(line, "wire3") && arg) {
            opt_wire3 = (arg[0] == 'o' && arg[1] == 'n');
            bb_gpio(opt_wire3);
            printf("3-wire 9-bit bit-banged framing %s (D/C pin %s)\r\n", opt_wire3 ? "ON" : "off",
                   opt_wire3 ? "held LOW" : "normal");
        }
        else if (!strcmp(line, "cs") && arg) { opt_cs_hold = (arg[0] == 'h'); printf("CS %s\r\n", opt_cs_hold ? "held across command+data (datasheet Fig 6-1)" : "toggles per write"); }
        else if (!strcmp(line, "entry") && arg) { opt_entry_mode = (arg[0] == '3') ? 0x03 : 0x01; do_status(); }
        else if (!strcmp(line, "mode") && arg) {
            if (!strcmp(arg, "color")) opt_update_mode = 0xF7;
            else if (!strcmp(arg, "bw")) opt_update_mode = 0xFF;
            else opt_update_mode = static_cast<uint8_t>(strtoul(arg, nullptr, 16));
            do_status();
        }
        else if (!strcmp(line, "watch")) watch(arg ? static_cast<uint32_t>(atoi(arg)) : 1000U);
        else if (!strcmp(line, "c") && arg) do_raw(arg);
        else printf("unknown command (try: help)\r\n");
    }
}
