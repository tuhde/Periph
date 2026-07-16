#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <string.h>
#include "I2CTransportZephyr.h"
#include "Rda5807m.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define RDA5807M_ADDR 0x10

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, RDA5807M_ADDR);
    RDA5807MFull fm(transport, 87.5f, 10);

    // --- FM band scanner ---
    // Start at the bottom of the world-wide band and repeatedly seek upward
    // with SKMODE=1 (stop at band limit) so a seek that returns false means
    // the top of the band has been reached and the scan is done.
    fm.enable_rds(true);

    printk("Scanning...\n");
    while (true) {
        float freq;
        if (!fm.seek(true, freq)) break;
        if (!fm.is_station()) continue;

        uint8_t rssi = fm.signal_strength();
        bool stereo = fm.is_stereo();

        // --- Try to read the Program Service (station) name via RDS ---
        // Group types 0A/0B carry the 8-character PS name, four segments of
        // two characters each, addressed by block B bits 1:0. Give the
        // decoder up to 2 seconds to assemble a full name.
        char ps_name[9];
        memset(ps_name, 0, sizeof(ps_name));
        bool have_all[4] = {false, false, false, false};
        int64_t deadline = k_uptime_get() + 2000;
        while (k_uptime_get() < deadline) {
            if (fm.rds_ready()) {
                uint16_t a, b, c, d;
                if (fm.read_rds_group(a, b, c, d)) {
                    uint8_t group_type = b >> 12;
                    uint8_t is_b_variant = (b >> 11) & 1;
                    if (group_type == 0 && is_b_variant == 0) {
                        uint8_t seg = b & 0x03;
                        ps_name[seg * 2] = static_cast<char>(d >> 8);
                        ps_name[seg * 2 + 1] = static_cast<char>(d & 0xFF);
                        have_all[seg] = true;
                        if (have_all[0] && have_all[1] && have_all[2] && have_all[3]) break;
                    }
                }
            }
            k_sleep(K_MSEC(40));
        }

        bool full_name = have_all[0] && have_all[1] && have_all[2] && have_all[3];
        printk("%d Hz/100  RSSI=%d  %s  %s\n", (int)(freq * 100), rssi,
               stereo ? "stereo" : "mono", full_name ? ps_name : "(no RDS name)");
    }
    printk("Scan complete.\n");

    while (1) {
        k_sleep(K_SECONDS(1));
    }
    return 0;
}
