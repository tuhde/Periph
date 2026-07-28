#pragma once
#include <stdint.h>

/** @brief Delivers edge notifications from a chip's INT line.
 *
 * Intentionally minimal: it only signals that *an* edge occurred. The chip
 * driver always calls pollInterrupt() to determine the cause. Multiple
 * handlers may be registered on the same InputPin instance (up to
 * kMaxHandlers) — the common pattern for chips with open-drain INT outputs
 * wired to a single GPIO. Handlers are plain function pointers (no
 * std::function, no heap allocation) to stay usable from ISR context on
 * every platform.
 */
class InputPin {
public:
    // Named kFalling/kRising/kChange (not FALLING/RISING/CHANGE) because those
    // bare names are #defined as macros by <Arduino.h> (and by other vendor
    // GPIO headers); a plain-macro FALLING would corrupt every qualified
    // InputPin::FALLING reference via raw token substitution before the
    // compiler ever sees the "::". kFalling/kRising/kChange do not collide.
    static constexpr uint8_t kFalling = 0;
    static constexpr uint8_t kRising  = 1;
    static constexpr uint8_t kChange  = 2;

    /** @brief Maximum handlers one InputPin instance can fan out to. */
    static constexpr uint8_t kMaxHandlers = 4;

    typedef void (*Handler)();

    virtual ~InputPin() = default;

    /** @brief Append handler to the edge-notification list for the given trigger.
     *  @param handler Zero-argument function invoked on each matching edge.
     *  @param trigger One of kFalling, kRising, kChange (default kFalling).
     *  @return true if registered; false if kMaxHandlers is already reached.
     */
    virtual bool onEdge(Handler handler, uint8_t trigger = kFalling) = 0;

    /** @brief Remove a specific handler from the list. No-op if not registered.
     *  @param handler The exact function pointer previously passed to onEdge().
     */
    virtual void offEdge(Handler handler) = 0;

protected:
    /** @brief Shared fan-out helper: appends to a fixed-size handler array.
     *  @return true if there was a free slot, false if the array was full.
     */
    static bool addHandler(Handler (&handlers)[kMaxHandlers], Handler h) {
        for (uint8_t i = 0; i < kMaxHandlers; ++i) {
            if (handlers[i] == nullptr) {
                handlers[i] = h;
                return true;
            }
        }
        return false;
    }

    /** @brief Shared fan-out helper: removes a handler from a fixed-size array. */
    static void removeHandler(Handler (&handlers)[kMaxHandlers], Handler h) {
        for (uint8_t i = 0; i < kMaxHandlers; ++i) {
            if (handlers[i] == h) {
                handlers[i] = nullptr;
            }
        }
    }

    /** @brief Shared fan-out helper: calls every registered handler. */
    static void dispatch(Handler (&handlers)[kMaxHandlers]) {
        for (uint8_t i = 0; i < kMaxHandlers; ++i) {
            if (handlers[i] != nullptr) handlers[i]();
        }
    }
};
