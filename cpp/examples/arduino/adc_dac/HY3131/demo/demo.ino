#include <SPI.h>
#include <Periph.h>

#ifndef TEST_CS_PIN
#define TEST_CS_PIN 10
#endif

SPISettings settings(1000000, MSBFIRST, SPI_MODE0);                  // 1 MHz, MSB first, CPOL=0 CPHA=0
SPIConnection connection(SPI, TEST_CS_PIN, settings);                // Create SPI connection, (SPI, cs_pin=10, settings) → SPIConnection
HY3131Full* adc;
int32_t tcode_25;

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();
    adc = new HY3131Full(connection, 1.2f);                                 // Construct and initialise the HY3131, (connection, vref=1.2 V) → HY3131Full
    // --- Bench voltmeter with on-chip temperature and AC analysis ---
    // A 0–1.2 V source sits between PB2 and AGND. AD1 runs at ~40 Hz with the chopper on
    // for offset-free DC readings; AD2 + LPF + RMS + peak hold watch the same node for AC.
    adc->configure_ad1(4, 2, 0, 0, 0, 0);                                    // Configure AD1, (osr=7, chop=0, input_gain=0, ref_gain=0, offset=0, buffers=0) → void
    // --- One-point temperature calibration ---
    // Run the board at 25 °C (or replace with a stored constant) and remember the code.
    tcode_25 = adc->read_temperature_code();                         // Read temperature code, () → int32_t
    // --- Enable AC measurement chain on the same input ---
    adc->enable_ad2(5, 1, 0, false, false, 2, false);                        // Enable AD2, (osr=5, input_gain=1, ref_gain=0, chop=false, clk_div4=false, source=0, use_ad3=false) → void
    adc->enable_lpf(3);                                                      // Enable low-pass filter, (bw=0) → void
    adc->enable_rms();                                                       // Enable RMS converter, () → void
    adc->enable_peak_hold(0);                                                // Enable peak hold, (source=0) → void
}

void loop() {
    float v = adc->read_voltage();                                           // Read AD1 voltage, () → float V
    float t = adc->read_temperature(tcode_25);                               // Read die temperature, (tcode_25) → float °C
    float rms = adc->read_rms();                                             // Read RMS, () → float LSB
    int32_t pk_min, pk_max;
    adc->read_peak(pk_min, pk_max);                                          // Read peak hold, (min&, max&) → void 19-bit
    Serial.print("V="); Serial.println(v);
    Serial.print("T="); Serial.println(t);
    Serial.print("rms="); Serial.println(rms);
    Serial.print("p-p="); Serial.println(pk_max - pk_min);
    delay(1000);
}
