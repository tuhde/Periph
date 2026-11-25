#pragma once

/** @brief Drives a chip's hardware enable or power pin. */
class OutputPin {
public:
    virtual ~OutputPin() = default;

    /** @brief Drive the pin high (true) or low (false).
     *  @param high true to drive the pin high, false to drive it low.
     */
    virtual void set(bool high) = 0;

    /** @brief True if the most recent set() succeeded.
     *
     *  Mirrors Connection::ok(). Platforms whose GPIO API reports failures set it from the call's
     *  result in every operation; pins that cannot detect failures always report true.
     */
    bool ok() const { return _ok; }

protected:
    /** @brief Subclass helper: record whether the operation in progress succeeded (see ok()). */
    void _setOk(bool ok) { _ok = ok; }

private:
    bool _ok = true;
};
