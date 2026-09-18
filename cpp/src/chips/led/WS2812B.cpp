#include "WS2812B.h"

namespace {
    const uint8_t kChannelOrder[3] = {1, 0, 2};  // GRB: wire[0]=G, wire[1]=R, wire[2]=B
    const size_t  kResetBytes = 0;               // connection's own 16-byte reset (~53us) is enough
}

WS2812BMinimal::WS2812BMinimal(Connection& connection, size_t n)
    : NeoPixelRGBMinimal(connection, n, kChannelOrder, kResetBytes)
{}

WS2812BFull::WS2812BFull(Connection& connection, size_t n)
    : NeoPixelRGBFull(connection, n, kChannelOrder, kResetBytes)
{}
