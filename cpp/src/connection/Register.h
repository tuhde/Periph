#pragma once
#include <stdint.h>

/** @brief Interpret the low @p bits bits of @p value as two's-complement signed.
 *  @param value Unsigned raw register value.
 *  @param bits  Width of the value in bits (e.g. 16, 24).
 *  @return The signed interpretation of the low @p bits bits of @p value.
 */
inline int32_t toSigned(uint32_t value, uint8_t bits) {
    uint32_t signBit = 1u << (bits - 1);
    return static_cast<int32_t>((value & (signBit - 1)) - (value & signBit));
}
