package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.RegisterConnection

// Named aliases: one thin Minimal / Full class per MCP4XXX family member. They only preset
// the part; behaviour is identical to MCP4XXXMinimal / MCP4XXXFull.

/**
 * MCP4531 minimal driver: {@link MCP4XXXMinimal} with the part preset.
 */
@CompileStatic
class MCP4531Minimal extends MCP4XXXMinimal {
    /**
     * @param connection I²C connection bound to an address in the part's range
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    MCP4531Minimal(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d) {
        super(connection, 'MCP4531', rAb, rWiper)
    }
}

/**
 * MCP4531 full driver: {@link MCP4XXXFull} with the part preset.
 */
@CompileStatic
class MCP4531Full extends MCP4XXXFull {
    /**
     * @param connection  I²C connection bound to an address in the part's range
     * @param rAb         end-to-end resistance RAB in ohms
     * @param rWiper      wiper resistance RW in ohms
     * @param hvc         optional pin switching the external HVC/A0 circuit (may be null)
     * @param hvcSettleMs delay in ms between asserting hvc and the first SCL edge
     */
    MCP4531Full(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d,
            OutputPin hvc = null, int hvcSettleMs = 1) {
        super(connection, 'MCP4531', rAb, rWiper, hvc, hvcSettleMs)
    }
}

/**
 * MCP4532 minimal driver: {@link MCP4XXXMinimal} with the part preset.
 */
@CompileStatic
class MCP4532Minimal extends MCP4XXXMinimal {
    /**
     * @param connection I²C connection bound to an address in the part's range
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    MCP4532Minimal(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d) {
        super(connection, 'MCP4532', rAb, rWiper)
    }
}

/**
 * MCP4532 full driver: {@link MCP4XXXFull} with the part preset.
 */
@CompileStatic
class MCP4532Full extends MCP4XXXFull {
    /**
     * @param connection  I²C connection bound to an address in the part's range
     * @param rAb         end-to-end resistance RAB in ohms
     * @param rWiper      wiper resistance RW in ohms
     * @param hvc         optional pin switching the external HVC/A0 circuit (may be null)
     * @param hvcSettleMs delay in ms between asserting hvc and the first SCL edge
     */
    MCP4532Full(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d,
            OutputPin hvc = null, int hvcSettleMs = 1) {
        super(connection, 'MCP4532', rAb, rWiper, hvc, hvcSettleMs)
    }
}

/**
 * MCP4551 minimal driver: {@link MCP4XXXMinimal} with the part preset.
 */
@CompileStatic
class MCP4551Minimal extends MCP4XXXMinimal {
    /**
     * @param connection I²C connection bound to an address in the part's range
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    MCP4551Minimal(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d) {
        super(connection, 'MCP4551', rAb, rWiper)
    }
}

/**
 * MCP4551 full driver: {@link MCP4XXXFull} with the part preset.
 */
@CompileStatic
class MCP4551Full extends MCP4XXXFull {
    /**
     * @param connection  I²C connection bound to an address in the part's range
     * @param rAb         end-to-end resistance RAB in ohms
     * @param rWiper      wiper resistance RW in ohms
     * @param hvc         optional pin switching the external HVC/A0 circuit (may be null)
     * @param hvcSettleMs delay in ms between asserting hvc and the first SCL edge
     */
    MCP4551Full(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d,
            OutputPin hvc = null, int hvcSettleMs = 1) {
        super(connection, 'MCP4551', rAb, rWiper, hvc, hvcSettleMs)
    }
}

/**
 * MCP4552 minimal driver: {@link MCP4XXXMinimal} with the part preset.
 */
@CompileStatic
class MCP4552Minimal extends MCP4XXXMinimal {
    /**
     * @param connection I²C connection bound to an address in the part's range
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    MCP4552Minimal(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d) {
        super(connection, 'MCP4552', rAb, rWiper)
    }
}

/**
 * MCP4552 full driver: {@link MCP4XXXFull} with the part preset.
 */
@CompileStatic
class MCP4552Full extends MCP4XXXFull {
    /**
     * @param connection  I²C connection bound to an address in the part's range
     * @param rAb         end-to-end resistance RAB in ohms
     * @param rWiper      wiper resistance RW in ohms
     * @param hvc         optional pin switching the external HVC/A0 circuit (may be null)
     * @param hvcSettleMs delay in ms between asserting hvc and the first SCL edge
     */
    MCP4552Full(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d,
            OutputPin hvc = null, int hvcSettleMs = 1) {
        super(connection, 'MCP4552', rAb, rWiper, hvc, hvcSettleMs)
    }
}

/**
 * MCP4631 minimal driver: {@link MCP4XXXMinimal} with the part preset.
 */
@CompileStatic
class MCP4631Minimal extends MCP4XXXMinimal {
    /**
     * @param connection I²C connection bound to an address in the part's range
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    MCP4631Minimal(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d) {
        super(connection, 'MCP4631', rAb, rWiper)
    }
}

/**
 * MCP4631 full driver: {@link MCP4XXXFull} with the part preset.
 */
@CompileStatic
class MCP4631Full extends MCP4XXXFull {
    /**
     * @param connection  I²C connection bound to an address in the part's range
     * @param rAb         end-to-end resistance RAB in ohms
     * @param rWiper      wiper resistance RW in ohms
     * @param hvc         optional pin switching the external HVC/A0 circuit (may be null)
     * @param hvcSettleMs delay in ms between asserting hvc and the first SCL edge
     */
    MCP4631Full(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d,
            OutputPin hvc = null, int hvcSettleMs = 1) {
        super(connection, 'MCP4631', rAb, rWiper, hvc, hvcSettleMs)
    }
}

/**
 * MCP4632 minimal driver: {@link MCP4XXXMinimal} with the part preset.
 */
@CompileStatic
class MCP4632Minimal extends MCP4XXXMinimal {
    /**
     * @param connection I²C connection bound to an address in the part's range
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    MCP4632Minimal(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d) {
        super(connection, 'MCP4632', rAb, rWiper)
    }
}

/**
 * MCP4632 full driver: {@link MCP4XXXFull} with the part preset.
 */
@CompileStatic
class MCP4632Full extends MCP4XXXFull {
    /**
     * @param connection  I²C connection bound to an address in the part's range
     * @param rAb         end-to-end resistance RAB in ohms
     * @param rWiper      wiper resistance RW in ohms
     * @param hvc         optional pin switching the external HVC/A0 circuit (may be null)
     * @param hvcSettleMs delay in ms between asserting hvc and the first SCL edge
     */
    MCP4632Full(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d,
            OutputPin hvc = null, int hvcSettleMs = 1) {
        super(connection, 'MCP4632', rAb, rWiper, hvc, hvcSettleMs)
    }
}

/**
 * MCP4651 minimal driver: {@link MCP4XXXMinimal} with the part preset.
 */
@CompileStatic
class MCP4651Minimal extends MCP4XXXMinimal {
    /**
     * @param connection I²C connection bound to an address in the part's range
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    MCP4651Minimal(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d) {
        super(connection, 'MCP4651', rAb, rWiper)
    }
}

/**
 * MCP4651 full driver: {@link MCP4XXXFull} with the part preset.
 */
@CompileStatic
class MCP4651Full extends MCP4XXXFull {
    /**
     * @param connection  I²C connection bound to an address in the part's range
     * @param rAb         end-to-end resistance RAB in ohms
     * @param rWiper      wiper resistance RW in ohms
     * @param hvc         optional pin switching the external HVC/A0 circuit (may be null)
     * @param hvcSettleMs delay in ms between asserting hvc and the first SCL edge
     */
    MCP4651Full(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d,
            OutputPin hvc = null, int hvcSettleMs = 1) {
        super(connection, 'MCP4651', rAb, rWiper, hvc, hvcSettleMs)
    }
}

/**
 * MCP4652 minimal driver: {@link MCP4XXXMinimal} with the part preset.
 */
@CompileStatic
class MCP4652Minimal extends MCP4XXXMinimal {
    /**
     * @param connection I²C connection bound to an address in the part's range
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    MCP4652Minimal(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d) {
        super(connection, 'MCP4652', rAb, rWiper)
    }
}

/**
 * MCP4652 full driver: {@link MCP4XXXFull} with the part preset.
 */
@CompileStatic
class MCP4652Full extends MCP4XXXFull {
    /**
     * @param connection  I²C connection bound to an address in the part's range
     * @param rAb         end-to-end resistance RAB in ohms
     * @param rWiper      wiper resistance RW in ohms
     * @param hvc         optional pin switching the external HVC/A0 circuit (may be null)
     * @param hvcSettleMs delay in ms between asserting hvc and the first SCL edge
     */
    MCP4652Full(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d,
            OutputPin hvc = null, int hvcSettleMs = 1) {
        super(connection, 'MCP4652', rAb, rWiper, hvc, hvcSettleMs)
    }
}

/**
 * MCP4541 minimal driver: {@link MCP4XXXMinimal} with the part preset.
 */
@CompileStatic
class MCP4541Minimal extends MCP4XXXMinimal {
    /**
     * @param connection I²C connection bound to an address in the part's range
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    MCP4541Minimal(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d) {
        super(connection, 'MCP4541', rAb, rWiper)
    }
}

/**
 * MCP4541 full driver: {@link MCP4XXXFull} with the part preset.
 */
@CompileStatic
class MCP4541Full extends MCP4XXXFull {
    /**
     * @param connection  I²C connection bound to an address in the part's range
     * @param rAb         end-to-end resistance RAB in ohms
     * @param rWiper      wiper resistance RW in ohms
     * @param hvc         optional pin switching the external HVC/A0 circuit (may be null)
     * @param hvcSettleMs delay in ms between asserting hvc and the first SCL edge
     */
    MCP4541Full(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d,
            OutputPin hvc = null, int hvcSettleMs = 1) {
        super(connection, 'MCP4541', rAb, rWiper, hvc, hvcSettleMs)
    }
}

/**
 * MCP4542 minimal driver: {@link MCP4XXXMinimal} with the part preset.
 */
@CompileStatic
class MCP4542Minimal extends MCP4XXXMinimal {
    /**
     * @param connection I²C connection bound to an address in the part's range
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    MCP4542Minimal(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d) {
        super(connection, 'MCP4542', rAb, rWiper)
    }
}

/**
 * MCP4542 full driver: {@link MCP4XXXFull} with the part preset.
 */
@CompileStatic
class MCP4542Full extends MCP4XXXFull {
    /**
     * @param connection  I²C connection bound to an address in the part's range
     * @param rAb         end-to-end resistance RAB in ohms
     * @param rWiper      wiper resistance RW in ohms
     * @param hvc         optional pin switching the external HVC/A0 circuit (may be null)
     * @param hvcSettleMs delay in ms between asserting hvc and the first SCL edge
     */
    MCP4542Full(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d,
            OutputPin hvc = null, int hvcSettleMs = 1) {
        super(connection, 'MCP4542', rAb, rWiper, hvc, hvcSettleMs)
    }
}

/**
 * MCP4561 minimal driver: {@link MCP4XXXMinimal} with the part preset.
 */
@CompileStatic
class MCP4561Minimal extends MCP4XXXMinimal {
    /**
     * @param connection I²C connection bound to an address in the part's range
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    MCP4561Minimal(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d) {
        super(connection, 'MCP4561', rAb, rWiper)
    }
}

/**
 * MCP4561 full driver: {@link MCP4XXXFull} with the part preset.
 */
@CompileStatic
class MCP4561Full extends MCP4XXXFull {
    /**
     * @param connection  I²C connection bound to an address in the part's range
     * @param rAb         end-to-end resistance RAB in ohms
     * @param rWiper      wiper resistance RW in ohms
     * @param hvc         optional pin switching the external HVC/A0 circuit (may be null)
     * @param hvcSettleMs delay in ms between asserting hvc and the first SCL edge
     */
    MCP4561Full(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d,
            OutputPin hvc = null, int hvcSettleMs = 1) {
        super(connection, 'MCP4561', rAb, rWiper, hvc, hvcSettleMs)
    }
}

/**
 * MCP4562 minimal driver: {@link MCP4XXXMinimal} with the part preset.
 */
@CompileStatic
class MCP4562Minimal extends MCP4XXXMinimal {
    /**
     * @param connection I²C connection bound to an address in the part's range
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    MCP4562Minimal(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d) {
        super(connection, 'MCP4562', rAb, rWiper)
    }
}

/**
 * MCP4562 full driver: {@link MCP4XXXFull} with the part preset.
 */
@CompileStatic
class MCP4562Full extends MCP4XXXFull {
    /**
     * @param connection  I²C connection bound to an address in the part's range
     * @param rAb         end-to-end resistance RAB in ohms
     * @param rWiper      wiper resistance RW in ohms
     * @param hvc         optional pin switching the external HVC/A0 circuit (may be null)
     * @param hvcSettleMs delay in ms between asserting hvc and the first SCL edge
     */
    MCP4562Full(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d,
            OutputPin hvc = null, int hvcSettleMs = 1) {
        super(connection, 'MCP4562', rAb, rWiper, hvc, hvcSettleMs)
    }
}

/**
 * MCP4641 minimal driver: {@link MCP4XXXMinimal} with the part preset.
 */
@CompileStatic
class MCP4641Minimal extends MCP4XXXMinimal {
    /**
     * @param connection I²C connection bound to an address in the part's range
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    MCP4641Minimal(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d) {
        super(connection, 'MCP4641', rAb, rWiper)
    }
}

/**
 * MCP4641 full driver: {@link MCP4XXXFull} with the part preset.
 */
@CompileStatic
class MCP4641Full extends MCP4XXXFull {
    /**
     * @param connection  I²C connection bound to an address in the part's range
     * @param rAb         end-to-end resistance RAB in ohms
     * @param rWiper      wiper resistance RW in ohms
     * @param hvc         optional pin switching the external HVC/A0 circuit (may be null)
     * @param hvcSettleMs delay in ms between asserting hvc and the first SCL edge
     */
    MCP4641Full(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d,
            OutputPin hvc = null, int hvcSettleMs = 1) {
        super(connection, 'MCP4641', rAb, rWiper, hvc, hvcSettleMs)
    }
}

/**
 * MCP4642 minimal driver: {@link MCP4XXXMinimal} with the part preset.
 */
@CompileStatic
class MCP4642Minimal extends MCP4XXXMinimal {
    /**
     * @param connection I²C connection bound to an address in the part's range
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    MCP4642Minimal(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d) {
        super(connection, 'MCP4642', rAb, rWiper)
    }
}

/**
 * MCP4642 full driver: {@link MCP4XXXFull} with the part preset.
 */
@CompileStatic
class MCP4642Full extends MCP4XXXFull {
    /**
     * @param connection  I²C connection bound to an address in the part's range
     * @param rAb         end-to-end resistance RAB in ohms
     * @param rWiper      wiper resistance RW in ohms
     * @param hvc         optional pin switching the external HVC/A0 circuit (may be null)
     * @param hvcSettleMs delay in ms between asserting hvc and the first SCL edge
     */
    MCP4642Full(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d,
            OutputPin hvc = null, int hvcSettleMs = 1) {
        super(connection, 'MCP4642', rAb, rWiper, hvc, hvcSettleMs)
    }
}

/**
 * MCP4661 minimal driver: {@link MCP4XXXMinimal} with the part preset.
 */
@CompileStatic
class MCP4661Minimal extends MCP4XXXMinimal {
    /**
     * @param connection I²C connection bound to an address in the part's range
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    MCP4661Minimal(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d) {
        super(connection, 'MCP4661', rAb, rWiper)
    }
}

/**
 * MCP4661 full driver: {@link MCP4XXXFull} with the part preset.
 */
@CompileStatic
class MCP4661Full extends MCP4XXXFull {
    /**
     * @param connection  I²C connection bound to an address in the part's range
     * @param rAb         end-to-end resistance RAB in ohms
     * @param rWiper      wiper resistance RW in ohms
     * @param hvc         optional pin switching the external HVC/A0 circuit (may be null)
     * @param hvcSettleMs delay in ms between asserting hvc and the first SCL edge
     */
    MCP4661Full(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d,
            OutputPin hvc = null, int hvcSettleMs = 1) {
        super(connection, 'MCP4661', rAb, rWiper, hvc, hvcSettleMs)
    }
}

/**
 * MCP4662 minimal driver: {@link MCP4XXXMinimal} with the part preset.
 */
@CompileStatic
class MCP4662Minimal extends MCP4XXXMinimal {
    /**
     * @param connection I²C connection bound to an address in the part's range
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    MCP4662Minimal(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d) {
        super(connection, 'MCP4662', rAb, rWiper)
    }
}

/**
 * MCP4662 full driver: {@link MCP4XXXFull} with the part preset.
 */
@CompileStatic
class MCP4662Full extends MCP4XXXFull {
    /**
     * @param connection  I²C connection bound to an address in the part's range
     * @param rAb         end-to-end resistance RAB in ohms
     * @param rWiper      wiper resistance RW in ohms
     * @param hvc         optional pin switching the external HVC/A0 circuit (may be null)
     * @param hvcSettleMs delay in ms between asserting hvc and the first SCL edge
     */
    MCP4662Full(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 75.0d,
            OutputPin hvc = null, int hvcSettleMs = 1) {
        super(connection, 'MCP4662', rAb, rWiper, hvc, hvcSettleMs)
    }
}

