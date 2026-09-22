#include "WS2814.h"

namespace {
    const uint8_t kChannelOrder[4] = {0, 1, 2, 3};  // RGBW: identity, no reorder
    const size_t  kResetBytes = 90;                  // ~300us extended reset (>=280us required)
}

WS2814Minimal::WS2814Minimal(Connection& connection, size_t n)
    : NeoPixelRGBWMinimal(connection, n, kChannelOrder, kResetBytes)
{}

WS2814Full::WS2814Full(Connection& connection, size_t n)
    : NeoPixelRGBWFull(connection, n, kChannelOrder, kResetBytes)
{}
