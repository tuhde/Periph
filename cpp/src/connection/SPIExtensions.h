#pragma once
#include "Connection.h"

/** @brief Optional SPI operations beyond the base `Connection` contract.
 *
 * Required by SD cards in SPI mode (`specs/transport_spi.md`, "Optional
 * Extensions"). Implemented by SPI connections that can honour them; chips
 * that do not need them never call these.
 */
class SPIExtensions {
public:
    virtual ~SPIExtensions() = default;

    /** @brief The `Connection` that carries the data operations of this device. */
    virtual Connection& connection() = 0;

    /** @brief Clock @p n bits of 0xFF with CS deasserted (rounded up to whole bytes). */
    virtual void idle_clocks(uint32_t n) = 0;

    /** @brief Change SCK to the highest supported frequency not above @p hz.
     *  @return false if the platform cannot change the clock at run time.
     */
    virtual bool set_speed(uint32_t hz) = 0;

    /** @brief Assert CS and hold it across the following `write`/`read`/`write_read` calls. */
    virtual void select() = 0;

    /** @brief Release CS asserted by select(). */
    virtual void deselect() = 0;
};
