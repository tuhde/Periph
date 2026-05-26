#ifndef DHTXX_TRANSPORT_ZEPHYR_H
#define DHTXX_TRANSPORT_ZEPHYR_H

#include <zephyr/drivers/gpio.h>
#include <stdint.h>

class DHTxxTransportZephyr {
public:
    DHTxxTransportZephyr(const struct gpio_dt_spec* spec) : _spec(spec) {}

    bool read(uint8_t* frame, size_t len) {
        if (len < 5) {
            return false;
        }

        gpio_pin_configure_dt(_spec, GPIO_OUTPUT_ACTIVE);
        gpio_pin_set_dt(_spec, 0);
        k_busy_wait(T_HOST_LOW);
        gpio_pin_configure_dt(_spec, GPIO_INPUT);
        k_busy_wait(T_GO);

        if (waitLow(1000) < 0) {
            return false;
        }
        if (waitHigh(1000) < 0) {
            return false;
        }

        uint32_t bits = 0;
        for (int i = 0; i < 40; i++) {
            if (waitLow(1000) < 0) {
                return false;
            }
            int width = waitHigh(1000);
            if (width < 0) {
                return false;
            }
            bits = (bits << 1) | (width >= (int)T_THRESHOLD ? 1 : 0);
        }

        frame[0] = (bits >> 32) & 0xFF;
        frame[1] = (bits >> 24) & 0xFF;
        frame[2] = (bits >> 16) & 0xFF;
        frame[3] = (bits >> 8) & 0xFF;
        frame[4] = bits & 0xFF;

        return true;
    }

private:
    const struct gpio_dt_spec* _spec;
    static constexpr uint32_t T_HOST_LOW = 20000;
    static constexpr uint32_t T_GO = 20;
    static constexpr uint32_t T_THRESHOLD = 40;

    int waitLow(uint32_t timeout_us) {
        uint32_t start = k_cycle_get_32();
        while (gpio_pin_get_dt(_spec) == 1) {
            if (k_cyc_to_us_near32(k_cycle_get_32() - start) > (int)timeout_us) {
                return -1;
            }
        }
        return k_cyc_to_us_near32(k_cycle_get_32() - start);
    }

    int waitHigh(uint32_t timeout_us) {
        uint32_t start = k_cycle_get_32();
        while (gpio_pin_get_dt(_spec) == 0) {
            if (k_cyc_to_us_near32(k_cycle_get_32() - start) > (int)timeout_us) {
                return -1;
            }
        }
        return k_cyc_to_us_near32(k_cycle_get_32() - start);
    }
};

#endif
