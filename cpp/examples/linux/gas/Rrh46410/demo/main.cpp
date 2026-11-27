#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Rrh46410.h"

static void print_result(const RRH46410Result& r) {
    unsigned long tvoc = (unsigned long)(r.tvoc_mg_m3 * 1000.0f + 0.5f);
    unsigned long etoh = (unsigned long)(r.etoh_ppm * 1000.0f + 0.5f);
    unsigned iaq = (unsigned)(r.iaq * 10.0f + 0.5f);
    printf("sample %u  IAQ %u.%u  TVOC %lu.%03lu mg/m3  EtOH %lu.%03lu ppm  eCO2 %u ppm  rel %u  %s\n",
          (unsigned)r.sample, iaq / 10, iaq % 10, tvoc / 1000, tvoc % 1000, etoh / 1000, etoh % 1000,
          (unsigned)r.eco2_ppm, (unsigned)r.relative_iaq, r.warmed_up ? "ok" : "warming up");
}

static volatile bool g_sample_ready = false;
static void on_sample() { g_sample_ready = true; }

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x38;
    I2CConnectionLinux connection(bus, addr);

    RRH46410Full sensor(connection);                               // Create RRH46410 driver, (connection) → void
    sensor.init();                                                 // Check product ID, start IAQ 2nd Gen mode, () → bool

    // --- Identity ---
    // Get Product ID returns 0x2310 and the firmware version; the tracking number
    // identifies the individual module.
    uint16_t pid = 0;
    uint8_t fw[3] = {0};
    sensor.product_id(pid, fw);                                    // Product ID and firmware, (productId&, firmware[3]) → bool
    printf("product 0x%04X, firmware %u.%u.%u\n", (unsigned)pid, fw[0], fw[1], fw[2]);
    uint8_t tn[6];
    sensor.tracking_number(tn);                                    // Tracking number, (out[6]) → bool
    printf("tracking %02X%02X%02X%02X%02X%02X, mode %d\n", tn[0], tn[1], tn[2], tn[3], tn[4], tn[5], sensor.get_mode()); // Operation mode, () → int

    // --- Humidity compensation ---
    // The MOx element reacts to humidity; feeding the real value improves the
    // results. The module assumes 50 %RH.
    sensor.set_humidity(42.0f);                                    // Ambient humidity, (percent %RH) → bool

    // --- Indoor air-quality monitor ---
    // A new sample arrives every 3 s; the first 100 samples (about 5 min) are the
    // warm-up during which the values stay fixed.
    unsigned warmup = sensor.warmup_samples();                     // Warm-up length, (mode=-1) → uint8_t samples
    printf("sample period %u s, warm-up %u samples\n", (unsigned)sensor.sample_period(), warmup); // Sample period, (mode=-1) → float s
    RRH46410Result r;
    for (int i = 0; i < 20; i++) {
        if (!sensor.read(r)) continue;                             // Next sample, (result&) → bool
        print_result(r);
        printf("  rating %s, warm-up %u%%\n", RRH46410Full::air_quality_label(r.iaq), // UBA rating, (iaq) → const char*
              r.warmed_up ? 100u : (unsigned)r.sample * 100u / warmup);
    }

    // --- Interrupt instead of polling ---
    // With INT wired the driver reacts to its rising edge; otherwise poll_interrupt
    // compares the sample counter.
    g_sample_ready = false;
    sensor.on_interrupt(on_sample);                                // Subscribe to INT, (callback, intPin=nullptr) → void
    for (int i = 0; i < 100 && !g_sample_ready; i++) {
        if (sensor.poll_interrupt()) break;                        // New sample pending, () → bool
        usleep((unsigned)(100) * 1000);
    }
    sensor.off_interrupt();                                        // Unsubscribe, () → void
    if (sensor.wait_for_sample(4000) || sensor.read_results(r)) {  // Wait for a new sample, (timeoutMs) → bool; latest result, (result&) → bool
        printf("latest ");
        print_result(sensor.last_result());                        // Last decoded result, () → const RRH46410Result&
    }

    // --- Other operation modes ---
    // PBAQ reports TVOC and ethanol for public buildings; ULP saves power with
    // one sample every 90 s. Each mode change restarts the warm-up.
    const uint8_t modes[2] = {RRH46410Full::MODE_PBAQ, RRH46410Full::MODE_ULP};
    for (int i = 0; i < 2; i++) {
        sensor.set_mode(modes[i]);                                 // Operation mode, (mode) → bool
        printf("%s: sample every %u s, warm-up %u samples\n", i == 0 ? "PBAQ" : "ULP",
              (unsigned)sensor.sample_period(), (unsigned)sensor.warmup_samples()); // Mode timing, (mode=-1) → float s / uint8_t
    }
    sensor.set_mode(RRH46410Full::MODE_PBAQ);                      // Operation mode, (mode) → bool
    if (sensor.read(r)) print_result(r);                           // Next sample, (result&) → bool

    // --- GPIOs ---
    // GPIO1 and GPIO2 can drive an indicator LED or a fan relay.
    sensor.config_gpio(0x03);                                      // Both outputs, (directions) → bool
    for (int i = 0; i < 3; i++) {
        sensor.set_gpio(0x01);                                     // GPIO1 high, (mask) → bool
        usleep((unsigned)(200) * 1000);
        sensor.clear_gpio(0x01);                                   // GPIO1 low, (mask) → bool
        usleep((unsigned)(200) * 1000);
    }
    printf("GPIO levels 0x%02X, status 0x%02X\n", sensor.get_gpio(), sensor.status().raw); // GPIO levels, () → int; status, () → RRH46410Status
    uint8_t mode = 0;
    sensor.send_command(0x10, nullptr, 0, &mode, 1);               // Raw request, (command, params*, len, out*, responseLen) → int
    printf("raw mode %u\n", mode);

    // --- Wrap up ---
    // clean_sensor(true) runs the 1-minute cleaning once in the sensor's lifetime;
    // it is skipped here. Suspend stops measuring; reset restarts the module in SUSPEND.
    sensor.suspend();                                              // Suspend mode, () → bool
    sensor.reset();                                                // Reset, () → bool
    printf("mode after reset %d\n", sensor.get_mode());            // Operation mode, () → int
    return 0;
}
