#pragma once

/** @brief Drives a chip's hardware enable or power pin. */
class OutputPin {
public:
    virtual ~OutputPin() = default;

    /** @brief Drive the pin high (true) or low (false).
     *  @param high true to drive the pin high, false to drive it low.
     */
    virtual void set(bool high) = 0;
};
