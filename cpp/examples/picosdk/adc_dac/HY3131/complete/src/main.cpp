#include <stdio.h>
#include "pico/stdlib.h"
#include <hardware/spi.h>
#include "SPIConnectionPicoSDK.h"
#include "HY3131.h"

static const uint MOSI_PIN = 19;
static const uint MISO_PIN = 16;
static const uint SCLK_PIN = 18;
static const uint CS_PIN   = 5;

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 1000000);
    spi_set_format(spi0, 8, SPI_CPOL_0, SPI_CPHA_0, SPI_MSB_FIRST);
    gpio_set_function(MOSI_PIN, GPIO_FUNC_SPI);
    gpio_set_function(MISO_PIN, GPIO_FUNC_SPI);
    gpio_set_function(SCLK_PIN, GPIO_FUNC_SPI);

    SPIConnectionPicoSDK connection(spi0, CS_PIN);                            // Create SPI connection, (spi0, cs_pin=5) → SPIConnectionPicoSDK
    HY3131Full adc(connection, 1.2f);                                       // Construct and initialise the HY3131, (connection, vref=1.2 V) → HY3131Full

    adc.configure_ad1(4, 2, 0, 0, 0, 0);                                    // Configure AD1, (osr=7, chop=0, input_gain=0, ref_gain=0, offset=0, buffers=0) → void
                                                                            // OSR 4096 (~40 Hz), chopper on (offset cancelled, rate halved), gain 0.9
    adc.set_ad1_input(10, 3, 2);                                            // Select AD1 inputs, (pos=SAD1FP 0–15, neg=SAD1FN 0–7, filter_r=2) → void
                                                                            // PB2 vs AGND with the filter resistor bypassed
    adc.set_ad1_reference(1, 1);                                            // Select AD1 reference, (high=0–6, low=0–6) → void
                                                                            // REFO (+) / AGND (−)
    int32_t raw = adc.read_raw();                                           // Read AD1 code, () → int32_t 24-bit
                                                                            // waits for AD1F in INTF; returns TIMEOUT (INT32_MIN) after 2 s
    float v = adc.read_voltage();                                           // Read AD1 voltage, () → float V
                                                                            // code / 2^23 · RG − DCOS, scaled by vref / input gain
    printf("raw=%ld\n", (long)(raw));
    printf("v=%.4f\n", (double)(v));
    int32_t tcode = adc.read_temperature_code();                            // Read temperature code, () → int32_t
                                                                            // (AD1[TS pair 2] − AD1[TS pair 1]) / 2; note this value at 25 °C
    float t = adc.read_temperature(tcode);                                  // Read die temperature, (tcode_25) → float °C
                                                                            // one-point calibrated against tcode_25
    printf("temperature=%.4f\n", (double)(t));
    adc.enable_ad2(5, 1, 0, false, false, 0, false);                        // Enable AD2, (osr=5, input_gain=1, ref_gain=0, chop=false, clk_div4=false, source=0, use_ad3=false) → void
                                                                            // AD2 samples OP1O at gain 1.0, OSR 1024
    int32_t ad2 = adc.read_ad2_raw();                                       // Read AD2 code, () → int32_t 19-bit
                                                                            // waits for AD2F
    adc.enable_lpf(0);                                                      // Enable low-pass filter, (bw=0) → void
                                                                            // OSR4 = 128
    int32_t lpf = adc.read_lpf_raw();                                       // Read LPF code, () → int32_t 19-bit
                                                                            // waits for LPFF
    adc.enable_rms();                                                       // Enable RMS converter, () → void
                                                                            // accumulates the squared LPF input
    float rms = adc.read_rms();                                             // Read RMS, () → float LSB
                                                                            // square root of the accumulator
    adc.enable_peak_hold(0);                                                // Enable peak hold, (source=0) → void
                                                                            // tracks AD2 min/max; re-enabling resets
    int32_t pk_min, pk_max;
    adc.read_peak(pk_min, pk_max);                                          // Read peak hold, (min&, max&) → void 19-bit
    printf("ad2=%ld\n", (long)(ad2));
    printf("lpf=%ld\n", (long)(lpf));
    printf("rms=%.4f\n", (double)(rms));
    printf("peak_min=%ld\n", (long)(pk_min));
    printf("peak_max=%ld\n", (long)(pk_max));
    adc.start_counter(0);                                                   // Start frequency counter, (gate_preload=0) → void
                                                                            // enables the CNT buffer and the counter
    uint32_t cta, ctb, ctc; bool overflow;
    adc.read_counter(cta, ctb, ctc, overflow);                              // Read counter, (cta&, ctb&, ctc&, overflow&) → bool
                                                                            // waits for CTF; overflow=true means the measurement is invalid
    printf("cta=%ld\n", (long)((int32_t)cta));
    adc.enable_interrupts(0x04);                                            // Enable interrupts, (mask) → void
                                                                            // AD1IE: DO goes high while CS is high and AD1F is set
    uint8_t flags = adc.read_flags();                                       // Read and clear INTF, () → uint8_t
                                                                            // bit 2 = AD1F
    printf("flags=%ld\n", (long)(flags));
    adc.reset();                                                            // Software reset, () → void
                                                                            // writes 0x60 to R37 and re-applies the Minimal defaults

    while (true) {
        sleep_ms(1000);
    }
}
