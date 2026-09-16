#include "SK6812RGBW.h"

namespace {
    const uint8_t kChannelOrder[4] = {1, 0, 2, 3};  // GRBW: wire[0]=G, wire[1]=R, wire[2]=B, wire[3]=W
    const size_t  kResetBytes = 24;                  // ~80us extended reset
}

SK6812RGBWMinimal::SK6812RGBWMinimal(Connection& connection, size_t n)
    : NeoPixelRGBWMinimal(connection, n, kChannelOrder, kResetBytes)
{}

SK6812RGBWFull::SK6812RGBWFull(Connection& connection, size_t n)
    : NeoPixelRGBWFull(connection, n, kChannelOrder, kResetBytes)
{}
