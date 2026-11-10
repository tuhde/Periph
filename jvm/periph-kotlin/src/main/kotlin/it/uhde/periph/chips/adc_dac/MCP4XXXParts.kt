package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.RegisterConnection

// Named aliases: one thin Minimal / Full class per MCP4XXX family member. They only preset
// the part; behaviour is identical to MCP4XXXMinimal / MCP4XXXFull.

/**
 * MCP4531 minimal driver: [MCP4XXXMinimal] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 */
open class MCP4531Minimal(connection: RegisterConnection, rAb: Double = 10000.0, rWiper: Double = 75.0) :
    MCP4XXXMinimal(connection, "MCP4531", rAb, rWiper)

/**
 * MCP4531 full driver: [MCP4XXXFull] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 * @param hvc optional pin switching the external HVC/A0 circuit
 * @param hvcSettleMs delay in ms between asserting `hvc` and the first SCL edge
 */
open class MCP4531Full(
    connection: RegisterConnection,
    rAb: Double = 10000.0,
    rWiper: Double = 75.0,
    hvc: OutputPin? = null,
    hvcSettleMs: Long = 1
) : MCP4XXXFull(connection, "MCP4531", rAb, rWiper, hvc, hvcSettleMs)

/**
 * MCP4532 minimal driver: [MCP4XXXMinimal] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 */
open class MCP4532Minimal(connection: RegisterConnection, rAb: Double = 10000.0, rWiper: Double = 75.0) :
    MCP4XXXMinimal(connection, "MCP4532", rAb, rWiper)

/**
 * MCP4532 full driver: [MCP4XXXFull] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 * @param hvc optional pin switching the external HVC/A0 circuit
 * @param hvcSettleMs delay in ms between asserting `hvc` and the first SCL edge
 */
open class MCP4532Full(
    connection: RegisterConnection,
    rAb: Double = 10000.0,
    rWiper: Double = 75.0,
    hvc: OutputPin? = null,
    hvcSettleMs: Long = 1
) : MCP4XXXFull(connection, "MCP4532", rAb, rWiper, hvc, hvcSettleMs)

/**
 * MCP4551 minimal driver: [MCP4XXXMinimal] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 */
open class MCP4551Minimal(connection: RegisterConnection, rAb: Double = 10000.0, rWiper: Double = 75.0) :
    MCP4XXXMinimal(connection, "MCP4551", rAb, rWiper)

/**
 * MCP4551 full driver: [MCP4XXXFull] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 * @param hvc optional pin switching the external HVC/A0 circuit
 * @param hvcSettleMs delay in ms between asserting `hvc` and the first SCL edge
 */
open class MCP4551Full(
    connection: RegisterConnection,
    rAb: Double = 10000.0,
    rWiper: Double = 75.0,
    hvc: OutputPin? = null,
    hvcSettleMs: Long = 1
) : MCP4XXXFull(connection, "MCP4551", rAb, rWiper, hvc, hvcSettleMs)

/**
 * MCP4552 minimal driver: [MCP4XXXMinimal] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 */
open class MCP4552Minimal(connection: RegisterConnection, rAb: Double = 10000.0, rWiper: Double = 75.0) :
    MCP4XXXMinimal(connection, "MCP4552", rAb, rWiper)

/**
 * MCP4552 full driver: [MCP4XXXFull] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 * @param hvc optional pin switching the external HVC/A0 circuit
 * @param hvcSettleMs delay in ms between asserting `hvc` and the first SCL edge
 */
open class MCP4552Full(
    connection: RegisterConnection,
    rAb: Double = 10000.0,
    rWiper: Double = 75.0,
    hvc: OutputPin? = null,
    hvcSettleMs: Long = 1
) : MCP4XXXFull(connection, "MCP4552", rAb, rWiper, hvc, hvcSettleMs)

/**
 * MCP4631 minimal driver: [MCP4XXXMinimal] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 */
open class MCP4631Minimal(connection: RegisterConnection, rAb: Double = 10000.0, rWiper: Double = 75.0) :
    MCP4XXXMinimal(connection, "MCP4631", rAb, rWiper)

/**
 * MCP4631 full driver: [MCP4XXXFull] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 * @param hvc optional pin switching the external HVC/A0 circuit
 * @param hvcSettleMs delay in ms between asserting `hvc` and the first SCL edge
 */
open class MCP4631Full(
    connection: RegisterConnection,
    rAb: Double = 10000.0,
    rWiper: Double = 75.0,
    hvc: OutputPin? = null,
    hvcSettleMs: Long = 1
) : MCP4XXXFull(connection, "MCP4631", rAb, rWiper, hvc, hvcSettleMs)

/**
 * MCP4632 minimal driver: [MCP4XXXMinimal] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 */
open class MCP4632Minimal(connection: RegisterConnection, rAb: Double = 10000.0, rWiper: Double = 75.0) :
    MCP4XXXMinimal(connection, "MCP4632", rAb, rWiper)

/**
 * MCP4632 full driver: [MCP4XXXFull] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 * @param hvc optional pin switching the external HVC/A0 circuit
 * @param hvcSettleMs delay in ms between asserting `hvc` and the first SCL edge
 */
open class MCP4632Full(
    connection: RegisterConnection,
    rAb: Double = 10000.0,
    rWiper: Double = 75.0,
    hvc: OutputPin? = null,
    hvcSettleMs: Long = 1
) : MCP4XXXFull(connection, "MCP4632", rAb, rWiper, hvc, hvcSettleMs)

/**
 * MCP4651 minimal driver: [MCP4XXXMinimal] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 */
open class MCP4651Minimal(connection: RegisterConnection, rAb: Double = 10000.0, rWiper: Double = 75.0) :
    MCP4XXXMinimal(connection, "MCP4651", rAb, rWiper)

/**
 * MCP4651 full driver: [MCP4XXXFull] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 * @param hvc optional pin switching the external HVC/A0 circuit
 * @param hvcSettleMs delay in ms between asserting `hvc` and the first SCL edge
 */
open class MCP4651Full(
    connection: RegisterConnection,
    rAb: Double = 10000.0,
    rWiper: Double = 75.0,
    hvc: OutputPin? = null,
    hvcSettleMs: Long = 1
) : MCP4XXXFull(connection, "MCP4651", rAb, rWiper, hvc, hvcSettleMs)

/**
 * MCP4652 minimal driver: [MCP4XXXMinimal] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 */
open class MCP4652Minimal(connection: RegisterConnection, rAb: Double = 10000.0, rWiper: Double = 75.0) :
    MCP4XXXMinimal(connection, "MCP4652", rAb, rWiper)

/**
 * MCP4652 full driver: [MCP4XXXFull] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 * @param hvc optional pin switching the external HVC/A0 circuit
 * @param hvcSettleMs delay in ms between asserting `hvc` and the first SCL edge
 */
open class MCP4652Full(
    connection: RegisterConnection,
    rAb: Double = 10000.0,
    rWiper: Double = 75.0,
    hvc: OutputPin? = null,
    hvcSettleMs: Long = 1
) : MCP4XXXFull(connection, "MCP4652", rAb, rWiper, hvc, hvcSettleMs)

/**
 * MCP4541 minimal driver: [MCP4XXXMinimal] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 */
open class MCP4541Minimal(connection: RegisterConnection, rAb: Double = 10000.0, rWiper: Double = 75.0) :
    MCP4XXXMinimal(connection, "MCP4541", rAb, rWiper)

/**
 * MCP4541 full driver: [MCP4XXXFull] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 * @param hvc optional pin switching the external HVC/A0 circuit
 * @param hvcSettleMs delay in ms between asserting `hvc` and the first SCL edge
 */
open class MCP4541Full(
    connection: RegisterConnection,
    rAb: Double = 10000.0,
    rWiper: Double = 75.0,
    hvc: OutputPin? = null,
    hvcSettleMs: Long = 1
) : MCP4XXXFull(connection, "MCP4541", rAb, rWiper, hvc, hvcSettleMs)

/**
 * MCP4542 minimal driver: [MCP4XXXMinimal] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 */
open class MCP4542Minimal(connection: RegisterConnection, rAb: Double = 10000.0, rWiper: Double = 75.0) :
    MCP4XXXMinimal(connection, "MCP4542", rAb, rWiper)

/**
 * MCP4542 full driver: [MCP4XXXFull] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 * @param hvc optional pin switching the external HVC/A0 circuit
 * @param hvcSettleMs delay in ms between asserting `hvc` and the first SCL edge
 */
open class MCP4542Full(
    connection: RegisterConnection,
    rAb: Double = 10000.0,
    rWiper: Double = 75.0,
    hvc: OutputPin? = null,
    hvcSettleMs: Long = 1
) : MCP4XXXFull(connection, "MCP4542", rAb, rWiper, hvc, hvcSettleMs)

/**
 * MCP4561 minimal driver: [MCP4XXXMinimal] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 */
open class MCP4561Minimal(connection: RegisterConnection, rAb: Double = 10000.0, rWiper: Double = 75.0) :
    MCP4XXXMinimal(connection, "MCP4561", rAb, rWiper)

/**
 * MCP4561 full driver: [MCP4XXXFull] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 * @param hvc optional pin switching the external HVC/A0 circuit
 * @param hvcSettleMs delay in ms between asserting `hvc` and the first SCL edge
 */
open class MCP4561Full(
    connection: RegisterConnection,
    rAb: Double = 10000.0,
    rWiper: Double = 75.0,
    hvc: OutputPin? = null,
    hvcSettleMs: Long = 1
) : MCP4XXXFull(connection, "MCP4561", rAb, rWiper, hvc, hvcSettleMs)

/**
 * MCP4562 minimal driver: [MCP4XXXMinimal] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 */
open class MCP4562Minimal(connection: RegisterConnection, rAb: Double = 10000.0, rWiper: Double = 75.0) :
    MCP4XXXMinimal(connection, "MCP4562", rAb, rWiper)

/**
 * MCP4562 full driver: [MCP4XXXFull] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 * @param hvc optional pin switching the external HVC/A0 circuit
 * @param hvcSettleMs delay in ms between asserting `hvc` and the first SCL edge
 */
open class MCP4562Full(
    connection: RegisterConnection,
    rAb: Double = 10000.0,
    rWiper: Double = 75.0,
    hvc: OutputPin? = null,
    hvcSettleMs: Long = 1
) : MCP4XXXFull(connection, "MCP4562", rAb, rWiper, hvc, hvcSettleMs)

/**
 * MCP4641 minimal driver: [MCP4XXXMinimal] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 */
open class MCP4641Minimal(connection: RegisterConnection, rAb: Double = 10000.0, rWiper: Double = 75.0) :
    MCP4XXXMinimal(connection, "MCP4641", rAb, rWiper)

/**
 * MCP4641 full driver: [MCP4XXXFull] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 * @param hvc optional pin switching the external HVC/A0 circuit
 * @param hvcSettleMs delay in ms between asserting `hvc` and the first SCL edge
 */
open class MCP4641Full(
    connection: RegisterConnection,
    rAb: Double = 10000.0,
    rWiper: Double = 75.0,
    hvc: OutputPin? = null,
    hvcSettleMs: Long = 1
) : MCP4XXXFull(connection, "MCP4641", rAb, rWiper, hvc, hvcSettleMs)

/**
 * MCP4642 minimal driver: [MCP4XXXMinimal] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 */
open class MCP4642Minimal(connection: RegisterConnection, rAb: Double = 10000.0, rWiper: Double = 75.0) :
    MCP4XXXMinimal(connection, "MCP4642", rAb, rWiper)

/**
 * MCP4642 full driver: [MCP4XXXFull] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 * @param hvc optional pin switching the external HVC/A0 circuit
 * @param hvcSettleMs delay in ms between asserting `hvc` and the first SCL edge
 */
open class MCP4642Full(
    connection: RegisterConnection,
    rAb: Double = 10000.0,
    rWiper: Double = 75.0,
    hvc: OutputPin? = null,
    hvcSettleMs: Long = 1
) : MCP4XXXFull(connection, "MCP4642", rAb, rWiper, hvc, hvcSettleMs)

/**
 * MCP4661 minimal driver: [MCP4XXXMinimal] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 */
open class MCP4661Minimal(connection: RegisterConnection, rAb: Double = 10000.0, rWiper: Double = 75.0) :
    MCP4XXXMinimal(connection, "MCP4661", rAb, rWiper)

/**
 * MCP4661 full driver: [MCP4XXXFull] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 * @param hvc optional pin switching the external HVC/A0 circuit
 * @param hvcSettleMs delay in ms between asserting `hvc` and the first SCL edge
 */
open class MCP4661Full(
    connection: RegisterConnection,
    rAb: Double = 10000.0,
    rWiper: Double = 75.0,
    hvc: OutputPin? = null,
    hvcSettleMs: Long = 1
) : MCP4XXXFull(connection, "MCP4661", rAb, rWiper, hvc, hvcSettleMs)

/**
 * MCP4662 minimal driver: [MCP4XXXMinimal] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 */
open class MCP4662Minimal(connection: RegisterConnection, rAb: Double = 10000.0, rWiper: Double = 75.0) :
    MCP4XXXMinimal(connection, "MCP4662", rAb, rWiper)

/**
 * MCP4662 full driver: [MCP4XXXFull] with the part preset.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 * @param hvc optional pin switching the external HVC/A0 circuit
 * @param hvcSettleMs delay in ms between asserting `hvc` and the first SCL edge
 */
open class MCP4662Full(
    connection: RegisterConnection,
    rAb: Double = 10000.0,
    rWiper: Double = 75.0,
    hvc: OutputPin? = null,
    hvcSettleMs: Long = 1
) : MCP4XXXFull(connection, "MCP4662", rAb, rWiper, hvc, hvcSettleMs)

