#pragma once
#include <stdint.h>

/** @brief Convert HSV (each 0.0–1.0) to RGB integers 0–255. */
static inline void neopixel_hsv_to_rgb(float h, float s, float v,
                                        uint8_t& r, uint8_t& g, uint8_t& b) {
    if (s == 0.0f) {
        r = g = b = (uint8_t)(v * 255.0f);
        return;
    }
    int   i  = (int)(h * 6.0f);
    float f  = h * 6.0f - (float)i;
    uint8_t p  = (uint8_t)(v * (1.0f - s) * 255.0f);
    uint8_t q  = (uint8_t)(v * (1.0f - s * f) * 255.0f);
    uint8_t t  = (uint8_t)(v * (1.0f - s * (1.0f - f)) * 255.0f);
    uint8_t vv = (uint8_t)(v * 255.0f);
    switch (i % 6) {
        case 0: r = vv; g = t;  b = p;  break;
        case 1: r = q;  g = vv; b = p;  break;
        case 2: r = p;  g = vv; b = t;  break;
        case 3: r = p;  g = q;  b = vv; break;
        case 4: r = t;  g = p;  b = vv; break;
        default: r = vv; g = p; b = q;  break;
    }
}
