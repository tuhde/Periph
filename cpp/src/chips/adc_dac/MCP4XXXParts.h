#pragma once
#include "MCP4XXX.h"

// Named aliases: one thin Minimal / Full class per MCP4XXX family member. They only preset the
// part (and expose its default I²C address as DEFAULT_ADDRESS); behaviour is identical to
// MCP4XXXMinimal / MCP4XXXFull. Header-only, so no build file changes are needed.

/** @brief MCP4531 minimal interface: MCP4XXXMinimal with the part preset. */
class MCP4531Minimal : public MCP4XXXMinimal {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x2E;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab        End-to-end resistance RAB in ohms.
     * @param r_wiper     Wiper resistance RW in ohms.
     */
    explicit MCP4531Minimal(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f)
        : MCP4XXXMinimal(connection, MCP4XXXPart::MCP4531, r_ab, r_wiper) {}
};

/** @brief MCP4531 full interface: MCP4XXXFull with the part preset. */
class MCP4531Full : public MCP4XXXFull {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x2E;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection     RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab           End-to-end resistance RAB in ohms.
     * @param r_wiper        Wiper resistance RW in ohms.
     * @param hvc            Optional high-voltage command pin (non-volatile parts).
     * @param hvc_settle_ms  Delay in ms between asserting hvc and the first SCL edge.
     */
    explicit MCP4531Full(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f,
                      OutputPin* hvc = nullptr, unsigned hvc_settle_ms = 1)
        : MCP4XXXFull(connection, MCP4XXXPart::MCP4531, r_ab, r_wiper, hvc, hvc_settle_ms) {}
};

/** @brief MCP4532 minimal interface: MCP4XXXMinimal with the part preset. */
class MCP4532Minimal : public MCP4XXXMinimal {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x58;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab        End-to-end resistance RAB in ohms.
     * @param r_wiper     Wiper resistance RW in ohms.
     */
    explicit MCP4532Minimal(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f)
        : MCP4XXXMinimal(connection, MCP4XXXPart::MCP4532, r_ab, r_wiper) {}
};

/** @brief MCP4532 full interface: MCP4XXXFull with the part preset. */
class MCP4532Full : public MCP4XXXFull {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x58;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection     RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab           End-to-end resistance RAB in ohms.
     * @param r_wiper        Wiper resistance RW in ohms.
     * @param hvc            Optional high-voltage command pin (non-volatile parts).
     * @param hvc_settle_ms  Delay in ms between asserting hvc and the first SCL edge.
     */
    explicit MCP4532Full(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f,
                      OutputPin* hvc = nullptr, unsigned hvc_settle_ms = 1)
        : MCP4XXXFull(connection, MCP4XXXPart::MCP4532, r_ab, r_wiper, hvc, hvc_settle_ms) {}
};

/** @brief MCP4551 minimal interface: MCP4XXXMinimal with the part preset. */
class MCP4551Minimal : public MCP4XXXMinimal {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x2E;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab        End-to-end resistance RAB in ohms.
     * @param r_wiper     Wiper resistance RW in ohms.
     */
    explicit MCP4551Minimal(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f)
        : MCP4XXXMinimal(connection, MCP4XXXPart::MCP4551, r_ab, r_wiper) {}
};

/** @brief MCP4551 full interface: MCP4XXXFull with the part preset. */
class MCP4551Full : public MCP4XXXFull {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x2E;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection     RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab           End-to-end resistance RAB in ohms.
     * @param r_wiper        Wiper resistance RW in ohms.
     * @param hvc            Optional high-voltage command pin (non-volatile parts).
     * @param hvc_settle_ms  Delay in ms between asserting hvc and the first SCL edge.
     */
    explicit MCP4551Full(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f,
                      OutputPin* hvc = nullptr, unsigned hvc_settle_ms = 1)
        : MCP4XXXFull(connection, MCP4XXXPart::MCP4551, r_ab, r_wiper, hvc, hvc_settle_ms) {}
};

/** @brief MCP4552 minimal interface: MCP4XXXMinimal with the part preset. */
class MCP4552Minimal : public MCP4XXXMinimal {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x58;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab        End-to-end resistance RAB in ohms.
     * @param r_wiper     Wiper resistance RW in ohms.
     */
    explicit MCP4552Minimal(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f)
        : MCP4XXXMinimal(connection, MCP4XXXPart::MCP4552, r_ab, r_wiper) {}
};

/** @brief MCP4552 full interface: MCP4XXXFull with the part preset. */
class MCP4552Full : public MCP4XXXFull {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x58;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection     RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab           End-to-end resistance RAB in ohms.
     * @param r_wiper        Wiper resistance RW in ohms.
     * @param hvc            Optional high-voltage command pin (non-volatile parts).
     * @param hvc_settle_ms  Delay in ms between asserting hvc and the first SCL edge.
     */
    explicit MCP4552Full(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f,
                      OutputPin* hvc = nullptr, unsigned hvc_settle_ms = 1)
        : MCP4XXXFull(connection, MCP4XXXPart::MCP4552, r_ab, r_wiper, hvc, hvc_settle_ms) {}
};

/** @brief MCP4631 minimal interface: MCP4XXXMinimal with the part preset. */
class MCP4631Minimal : public MCP4XXXMinimal {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x28;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab        End-to-end resistance RAB in ohms.
     * @param r_wiper     Wiper resistance RW in ohms.
     */
    explicit MCP4631Minimal(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f)
        : MCP4XXXMinimal(connection, MCP4XXXPart::MCP4631, r_ab, r_wiper) {}
};

/** @brief MCP4631 full interface: MCP4XXXFull with the part preset. */
class MCP4631Full : public MCP4XXXFull {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x28;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection     RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab           End-to-end resistance RAB in ohms.
     * @param r_wiper        Wiper resistance RW in ohms.
     * @param hvc            Optional high-voltage command pin (non-volatile parts).
     * @param hvc_settle_ms  Delay in ms between asserting hvc and the first SCL edge.
     */
    explicit MCP4631Full(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f,
                      OutputPin* hvc = nullptr, unsigned hvc_settle_ms = 1)
        : MCP4XXXFull(connection, MCP4XXXPart::MCP4631, r_ab, r_wiper, hvc, hvc_settle_ms) {}
};

/** @brief MCP4632 minimal interface: MCP4XXXMinimal with the part preset. */
class MCP4632Minimal : public MCP4XXXMinimal {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x58;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab        End-to-end resistance RAB in ohms.
     * @param r_wiper     Wiper resistance RW in ohms.
     */
    explicit MCP4632Minimal(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f)
        : MCP4XXXMinimal(connection, MCP4XXXPart::MCP4632, r_ab, r_wiper) {}
};

/** @brief MCP4632 full interface: MCP4XXXFull with the part preset. */
class MCP4632Full : public MCP4XXXFull {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x58;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection     RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab           End-to-end resistance RAB in ohms.
     * @param r_wiper        Wiper resistance RW in ohms.
     * @param hvc            Optional high-voltage command pin (non-volatile parts).
     * @param hvc_settle_ms  Delay in ms between asserting hvc and the first SCL edge.
     */
    explicit MCP4632Full(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f,
                      OutputPin* hvc = nullptr, unsigned hvc_settle_ms = 1)
        : MCP4XXXFull(connection, MCP4XXXPart::MCP4632, r_ab, r_wiper, hvc, hvc_settle_ms) {}
};

/** @brief MCP4651 minimal interface: MCP4XXXMinimal with the part preset. */
class MCP4651Minimal : public MCP4XXXMinimal {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x28;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab        End-to-end resistance RAB in ohms.
     * @param r_wiper     Wiper resistance RW in ohms.
     */
    explicit MCP4651Minimal(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f)
        : MCP4XXXMinimal(connection, MCP4XXXPart::MCP4651, r_ab, r_wiper) {}
};

/** @brief MCP4651 full interface: MCP4XXXFull with the part preset. */
class MCP4651Full : public MCP4XXXFull {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x28;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection     RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab           End-to-end resistance RAB in ohms.
     * @param r_wiper        Wiper resistance RW in ohms.
     * @param hvc            Optional high-voltage command pin (non-volatile parts).
     * @param hvc_settle_ms  Delay in ms between asserting hvc and the first SCL edge.
     */
    explicit MCP4651Full(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f,
                      OutputPin* hvc = nullptr, unsigned hvc_settle_ms = 1)
        : MCP4XXXFull(connection, MCP4XXXPart::MCP4651, r_ab, r_wiper, hvc, hvc_settle_ms) {}
};

/** @brief MCP4652 minimal interface: MCP4XXXMinimal with the part preset. */
class MCP4652Minimal : public MCP4XXXMinimal {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x58;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab        End-to-end resistance RAB in ohms.
     * @param r_wiper     Wiper resistance RW in ohms.
     */
    explicit MCP4652Minimal(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f)
        : MCP4XXXMinimal(connection, MCP4XXXPart::MCP4652, r_ab, r_wiper) {}
};

/** @brief MCP4652 full interface: MCP4XXXFull with the part preset. */
class MCP4652Full : public MCP4XXXFull {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x58;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection     RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab           End-to-end resistance RAB in ohms.
     * @param r_wiper        Wiper resistance RW in ohms.
     * @param hvc            Optional high-voltage command pin (non-volatile parts).
     * @param hvc_settle_ms  Delay in ms between asserting hvc and the first SCL edge.
     */
    explicit MCP4652Full(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f,
                      OutputPin* hvc = nullptr, unsigned hvc_settle_ms = 1)
        : MCP4XXXFull(connection, MCP4XXXPart::MCP4652, r_ab, r_wiper, hvc, hvc_settle_ms) {}
};

/** @brief MCP4541 minimal interface: MCP4XXXMinimal with the part preset. */
class MCP4541Minimal : public MCP4XXXMinimal {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x2E;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab        End-to-end resistance RAB in ohms.
     * @param r_wiper     Wiper resistance RW in ohms.
     */
    explicit MCP4541Minimal(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f)
        : MCP4XXXMinimal(connection, MCP4XXXPart::MCP4541, r_ab, r_wiper) {}
};

/** @brief MCP4541 full interface: MCP4XXXFull with the part preset. */
class MCP4541Full : public MCP4XXXFull {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x2E;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection     RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab           End-to-end resistance RAB in ohms.
     * @param r_wiper        Wiper resistance RW in ohms.
     * @param hvc            Optional high-voltage command pin (non-volatile parts).
     * @param hvc_settle_ms  Delay in ms between asserting hvc and the first SCL edge.
     */
    explicit MCP4541Full(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f,
                      OutputPin* hvc = nullptr, unsigned hvc_settle_ms = 1)
        : MCP4XXXFull(connection, MCP4XXXPart::MCP4541, r_ab, r_wiper, hvc, hvc_settle_ms) {}
};

/** @brief MCP4542 minimal interface: MCP4XXXMinimal with the part preset. */
class MCP4542Minimal : public MCP4XXXMinimal {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x58;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab        End-to-end resistance RAB in ohms.
     * @param r_wiper     Wiper resistance RW in ohms.
     */
    explicit MCP4542Minimal(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f)
        : MCP4XXXMinimal(connection, MCP4XXXPart::MCP4542, r_ab, r_wiper) {}
};

/** @brief MCP4542 full interface: MCP4XXXFull with the part preset. */
class MCP4542Full : public MCP4XXXFull {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x58;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection     RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab           End-to-end resistance RAB in ohms.
     * @param r_wiper        Wiper resistance RW in ohms.
     * @param hvc            Optional high-voltage command pin (non-volatile parts).
     * @param hvc_settle_ms  Delay in ms between asserting hvc and the first SCL edge.
     */
    explicit MCP4542Full(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f,
                      OutputPin* hvc = nullptr, unsigned hvc_settle_ms = 1)
        : MCP4XXXFull(connection, MCP4XXXPart::MCP4542, r_ab, r_wiper, hvc, hvc_settle_ms) {}
};

/** @brief MCP4561 minimal interface: MCP4XXXMinimal with the part preset. */
class MCP4561Minimal : public MCP4XXXMinimal {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x2E;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab        End-to-end resistance RAB in ohms.
     * @param r_wiper     Wiper resistance RW in ohms.
     */
    explicit MCP4561Minimal(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f)
        : MCP4XXXMinimal(connection, MCP4XXXPart::MCP4561, r_ab, r_wiper) {}
};

/** @brief MCP4561 full interface: MCP4XXXFull with the part preset. */
class MCP4561Full : public MCP4XXXFull {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x2E;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection     RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab           End-to-end resistance RAB in ohms.
     * @param r_wiper        Wiper resistance RW in ohms.
     * @param hvc            Optional high-voltage command pin (non-volatile parts).
     * @param hvc_settle_ms  Delay in ms between asserting hvc and the first SCL edge.
     */
    explicit MCP4561Full(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f,
                      OutputPin* hvc = nullptr, unsigned hvc_settle_ms = 1)
        : MCP4XXXFull(connection, MCP4XXXPart::MCP4561, r_ab, r_wiper, hvc, hvc_settle_ms) {}
};

/** @brief MCP4562 minimal interface: MCP4XXXMinimal with the part preset. */
class MCP4562Minimal : public MCP4XXXMinimal {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x58;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab        End-to-end resistance RAB in ohms.
     * @param r_wiper     Wiper resistance RW in ohms.
     */
    explicit MCP4562Minimal(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f)
        : MCP4XXXMinimal(connection, MCP4XXXPart::MCP4562, r_ab, r_wiper) {}
};

/** @brief MCP4562 full interface: MCP4XXXFull with the part preset. */
class MCP4562Full : public MCP4XXXFull {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x58;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection     RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab           End-to-end resistance RAB in ohms.
     * @param r_wiper        Wiper resistance RW in ohms.
     * @param hvc            Optional high-voltage command pin (non-volatile parts).
     * @param hvc_settle_ms  Delay in ms between asserting hvc and the first SCL edge.
     */
    explicit MCP4562Full(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f,
                      OutputPin* hvc = nullptr, unsigned hvc_settle_ms = 1)
        : MCP4XXXFull(connection, MCP4XXXPart::MCP4562, r_ab, r_wiper, hvc, hvc_settle_ms) {}
};

/** @brief MCP4641 minimal interface: MCP4XXXMinimal with the part preset. */
class MCP4641Minimal : public MCP4XXXMinimal {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x28;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab        End-to-end resistance RAB in ohms.
     * @param r_wiper     Wiper resistance RW in ohms.
     */
    explicit MCP4641Minimal(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f)
        : MCP4XXXMinimal(connection, MCP4XXXPart::MCP4641, r_ab, r_wiper) {}
};

/** @brief MCP4641 full interface: MCP4XXXFull with the part preset. */
class MCP4641Full : public MCP4XXXFull {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x28;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection     RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab           End-to-end resistance RAB in ohms.
     * @param r_wiper        Wiper resistance RW in ohms.
     * @param hvc            Optional high-voltage command pin (non-volatile parts).
     * @param hvc_settle_ms  Delay in ms between asserting hvc and the first SCL edge.
     */
    explicit MCP4641Full(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f,
                      OutputPin* hvc = nullptr, unsigned hvc_settle_ms = 1)
        : MCP4XXXFull(connection, MCP4XXXPart::MCP4641, r_ab, r_wiper, hvc, hvc_settle_ms) {}
};

/** @brief MCP4642 minimal interface: MCP4XXXMinimal with the part preset. */
class MCP4642Minimal : public MCP4XXXMinimal {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x58;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab        End-to-end resistance RAB in ohms.
     * @param r_wiper     Wiper resistance RW in ohms.
     */
    explicit MCP4642Minimal(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f)
        : MCP4XXXMinimal(connection, MCP4XXXPart::MCP4642, r_ab, r_wiper) {}
};

/** @brief MCP4642 full interface: MCP4XXXFull with the part preset. */
class MCP4642Full : public MCP4XXXFull {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x58;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection     RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab           End-to-end resistance RAB in ohms.
     * @param r_wiper        Wiper resistance RW in ohms.
     * @param hvc            Optional high-voltage command pin (non-volatile parts).
     * @param hvc_settle_ms  Delay in ms between asserting hvc and the first SCL edge.
     */
    explicit MCP4642Full(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f,
                      OutputPin* hvc = nullptr, unsigned hvc_settle_ms = 1)
        : MCP4XXXFull(connection, MCP4XXXPart::MCP4642, r_ab, r_wiper, hvc, hvc_settle_ms) {}
};

/** @brief MCP4661 minimal interface: MCP4XXXMinimal with the part preset. */
class MCP4661Minimal : public MCP4XXXMinimal {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x28;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab        End-to-end resistance RAB in ohms.
     * @param r_wiper     Wiper resistance RW in ohms.
     */
    explicit MCP4661Minimal(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f)
        : MCP4XXXMinimal(connection, MCP4XXXPart::MCP4661, r_ab, r_wiper) {}
};

/** @brief MCP4661 full interface: MCP4XXXFull with the part preset. */
class MCP4661Full : public MCP4XXXFull {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x28;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection     RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab           End-to-end resistance RAB in ohms.
     * @param r_wiper        Wiper resistance RW in ohms.
     * @param hvc            Optional high-voltage command pin (non-volatile parts).
     * @param hvc_settle_ms  Delay in ms between asserting hvc and the first SCL edge.
     */
    explicit MCP4661Full(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f,
                      OutputPin* hvc = nullptr, unsigned hvc_settle_ms = 1)
        : MCP4XXXFull(connection, MCP4XXXPart::MCP4661, r_ab, r_wiper, hvc, hvc_settle_ms) {}
};

/** @brief MCP4662 minimal interface: MCP4XXXMinimal with the part preset. */
class MCP4662Minimal : public MCP4XXXMinimal {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x58;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection  RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab        End-to-end resistance RAB in ohms.
     * @param r_wiper     Wiper resistance RW in ohms.
     */
    explicit MCP4662Minimal(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f)
        : MCP4XXXMinimal(connection, MCP4XXXPart::MCP4662, r_ab, r_wiper) {}
};

/** @brief MCP4662 full interface: MCP4XXXFull with the part preset. */
class MCP4662Full : public MCP4XXXFull {
public:
    /** @brief Default 7-bit I²C address (all address pins low). */
    static constexpr uint8_t DEFAULT_ADDRESS = 0x58;
    /**
     * @brief Construct the driver; performs no I²C traffic.
     * @param connection     RegisterConnection (I²C) bound to an address in the part's range.
     * @param r_ab           End-to-end resistance RAB in ohms.
     * @param r_wiper        Wiper resistance RW in ohms.
     * @param hvc            Optional high-voltage command pin (non-volatile parts).
     * @param hvc_settle_ms  Delay in ms between asserting hvc and the first SCL edge.
     */
    explicit MCP4662Full(RegisterConnection& connection, float r_ab = 10000.0f, float r_wiper = 75.0f,
                      OutputPin* hvc = nullptr, unsigned hvc_settle_ms = 1)
        : MCP4XXXFull(connection, MCP4XXXPart::MCP4662, r_ab, r_wiper, hvc, hvc_settle_ms) {}
};

