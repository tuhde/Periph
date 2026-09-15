// Package comms contains drivers for Wireless and wired communication modules (FM tuner, etc.).
package comms

import (
	"errors"
	"fmt"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// Register addresses (LoRa mode).
const (
	rfm9xRegFifo          = 0x00
	rfm9xRegOpMode        = 0x01
	rfm9xRegFrfMsb        = 0x06
	rfm9xRegFrfMid        = 0x07
	rfm9xRegFrfLsb        = 0x08
	rfm9xRegPaConfig      = 0x09
	rfm9xRegOcp           = 0x0B
	rfm9xRegLna           = 0x0C
	rfm9xRegFifoAddrPtr   = 0x0D
	rfm9xRegFifoTxBase    = 0x0E
	rfm9xRegFifoRxBase    = 0x0F
	rfm9xRegFifoRxCurrent = 0x10
	rfm9xRegIrqFlags      = 0x12
	rfm9xRegRxNbBytes     = 0x13
	rfm9xRegPktSnr        = 0x19
	rfm9xRegPktRssi       = 0x1A
	rfm9xRegRssi          = 0x1B
	rfm9xRegModemConfig1  = 0x1D
	rfm9xRegModemConfig2  = 0x1E
	rfm9xRegPreambleMsb   = 0x20
	rfm9xRegPreambleLsb   = 0x21
	rfm9xRegPayloadLength = 0x22
	rfm9xRegModemConfig3  = 0x26
	rfm9xRegDetectionOpt  = 0x31
	rfm9xRegDetectionThr  = 0x37
	rfm9xRegDioMapping1   = 0x40
	rfm9xRegVersion       = 0x42
	rfm9xRegPaDac         = 0x4D
)

// RegOpMode mode bits (LongRangeMode already OR'd in by callers).
const (
	rfm9xModeLongRange = 0x80
	rfm9xModeSleep     = 0x00
	rfm9xModeStandby   = 0x01
	rfm9xModeTx        = 0x03
	rfm9xModeRxCont    = 0x05
	rfm9xModeRxSingle  = 0x06
)

// RegIrqFlags bits.
const (
	rfm9xIrqTxDone    = 0x08
	rfm9xIrqRxDone    = 0x40
	rfm9xIrqRxTimeout = 0x80
)

// RegPaConfig / RegPaDac / RegOcp values.
const (
	rfm9xPaBoost        = 0x80
	rfm9xPaDacHighPower = 0x87
	rfm9xPaDacDefault   = 0x84
	rfm9xOcp240mA       = 0x3B
	rfm9xOcpDefault     = 0x2B
)

// RegDioMapping1 values selecting DIO0's signal.
const (
	rfm9xDIO0RxDone = 0x00
	rfm9xDIO0TxDone = 0x40
)

const (
	rfm9xFXOSC           uint64 = 32_000_000
	rfm9xExpectedVersion uint8  = 0x12
)

// rfm9xBwTable maps a signal bandwidth in kHz to its RegModemConfig1[7:4]
// code. Keys must match a caller's bandwidthKHz argument exactly.
var rfm9xBwTable = map[float64]uint8{
	7.8: 0, 10.4: 1, 15.6: 2, 20.8: 3, 31.25: 4,
	41.7: 5, 62.5: 6, 125.0: 7, 250.0: 8, 500.0: 9,
}

// Variant limits: frequency range, maximum spreading factor, and band flag.
const (
	rfm95FreqMinHz uint32 = 862_000_000
	rfm95FreqMaxHz uint32 = 1_020_000_000
	rfm95MaxSF     uint8  = 12

	rfm96FreqMinHz uint32 = 410_000_000
	rfm96FreqMaxHz uint32 = 525_000_000
	rfm96MaxSF     uint8  = 12

	rfm97FreqMinHz uint32 = 862_000_000
	rfm97FreqMaxHz uint32 = 1_020_000_000
	rfm97MaxSF     uint8  = 9

	rfm98FreqMinHz uint32 = 410_000_000
	rfm98FreqMaxHz uint32 = 525_000_000
	rfm98MaxSF     uint8  = 12
)

// rfm9xBase holds the connection, the variant's frequency/SF/band limits,
// and every register-level operation shared by RFM95Minimal, RFM96Minimal,
// RFM97Minimal, and RFM98Minimal (and, through them, the four *Full types).
//
// All four RFM95/96/97/98W modules share identical pins, register maps,
// SPI protocol, and LoRa modem logic. They differ only in supported
// frequency bands and, for RFM97W, the maximum spreading factor — so
// rfm9xBase takes those as constructor fields (freqMinHz, freqMaxHz,
// maxSF, lfBand) rather than compile-time constants, since Go structs
// have no per-subtype class attributes the way Python's variant
// subclasses do.
//
// Only Send and the Minimal-stage Receive are exported on rfm9xBase, so
// RFM95Minimal (etc.), which embeds it, promotes just those two.
// Everything Full-only — configure, setFrequency, setTxPower, reset,
// standby, sleep, version, rssi, lastPacketRSSI, lastPacketSNR,
// receiveInterrupt, receiveContinuous, readPacket, stopReceive — stays
// unexported, so it is invisible outside this package; RFM95Full (etc.)
// embeds *RFM95Minimal (promoting Send and the unexported helpers) and
// adds its own thin exported methods that delegate to those
// promoted-but-unexported methods, plus its own Receive that shadows the
// promoted one with an added useInterrupt parameter. No register-level
// logic is duplicated between Minimal and Full, or between the four
// variants.
type rfm9xBase struct {
	conn     connection.Connection
	resetPin connection.OutputPin // nil if NRESET is not wired
	dio0Pin  connection.InputPin  // nil if DIO0 is not wired

	freqMinHz uint32
	freqMaxHz uint32
	maxSF     uint8
	lfBand    bool

	frequencyHz uint32
	continuous  bool
}

// newRFM9xBase validates frequencyHz against the variant's range, runs the
// hardware-or-timed reset, and runs the LoRa init sequence.
func newRFM9xBase(conn connection.Connection, frequencyHz, freqMinHz, freqMaxHz uint32, maxSF uint8, lfBand bool, resetPin connection.OutputPin, dio0Pin connection.InputPin) (*rfm9xBase, error) {
	if frequencyHz < freqMinHz || frequencyHz > freqMaxHz {
		return nil, fmt.Errorf("rfm9x: frequency_hz %d out of range [%d, %d]", frequencyHz, freqMinHz, freqMaxHz)
	}
	b := &rfm9xBase{
		conn:        conn,
		resetPin:    resetPin,
		dio0Pin:     dio0Pin,
		freqMinHz:   freqMinHz,
		freqMaxHz:   freqMaxHz,
		maxSF:       maxSF,
		lfBand:      lfBand,
		frequencyHz: frequencyHz,
	}
	if resetPin != nil {
		if err := b.resetViaPin(); err != nil {
			return nil, err
		}
	} else {
		time.Sleep(10 * time.Millisecond)
	}
	if err := b.initRegisters(); err != nil {
		return nil, err
	}
	return b, nil
}

// resetViaPin pulses NRESET low then releases it, per the datasheet's
// manual-reset timing (>100 µs low, 5 ms settle after release).
func (b *rfm9xBase) resetViaPin() error {
	if err := b.resetPin.Set(false); err != nil {
		return err
	}
	time.Sleep(time.Millisecond)
	if err := b.resetPin.Set(true); err != nil {
		return err
	}
	time.Sleep(5 * time.Millisecond)
	return nil
}

// writeReg writes a single register (address byte with WNR=1, then data).
func (b *rfm9xBase) writeReg(reg, value uint8) error {
	return b.conn.Write([]byte{reg | 0x80, value})
}

// readReg reads a single register (address byte with WNR=0, then one
// data byte in the same transfer).
func (b *rfm9xBase) readReg(reg uint8) (uint8, error) {
	buf, err := b.conn.WriteRead([]byte{reg & 0x7F}, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

// burstWrite writes data starting at reg, relying on the chip's SPI
// auto-increment (except for RegFifo, which the caller pre-positions via
// RegFifoAddrPtr).
func (b *rfm9xBase) burstWrite(reg uint8, data []byte) error {
	buf := make([]byte, 1+len(data))
	buf[0] = reg | 0x80
	copy(buf[1:], data)
	return b.conn.Write(buf)
}

// burstRead reads n bytes starting at reg.
func (b *rfm9xBase) burstRead(reg uint8, n int) ([]byte, error) {
	return b.conn.WriteRead([]byte{reg & 0x7F}, n)
}

// bandFlag returns RegOpMode bit 3 (LowFrequencyModeOn) for this variant:
// set for HF variants, clear for LF variants. This mirrors the reference
// driver's register-level behavior exactly (see rfm9x.py's
// `0x08 if not self._LF_BAND else 0x00`).
func (b *rfm9xBase) bandFlag() uint8 {
	if b.lfBand {
		return 0x00
	}
	return 0x08
}

// enterLoRaSleep runs the mode-change-to-LoRa sequence: FSK SLEEP first,
// then LoRa SLEEP. Writing LoRa STANDBY directly, without going through
// SLEEP, does not switch the chip into LoRa mode (LongRangeMode is only
// writable in SLEEP mode).
func (b *rfm9xBase) enterLoRaSleep() error {
	if err := b.writeReg(rfm9xRegOpMode, 0x00); err != nil {
		return err
	}
	time.Sleep(time.Microsecond)
	if err := b.writeReg(rfm9xRegOpMode, rfm9xModeLongRange|b.bandFlag()|rfm9xModeSleep); err != nil {
		return err
	}
	time.Sleep(time.Microsecond)
	return nil
}

// initRegisters runs the full LoRa init sequence: verify RegVersion,
// switch to LoRa mode, set LNA boost and AGC, split the FIFO, set the
// carrier frequency, apply the Minimal-stage modem defaults (SF7, 125 kHz,
// CR 4/5, explicit header, CRC on, preamble 8), set TX power to +17 dBm on
// PA_BOOST, and enter STANDBY. Also used by reset() to re-run the sequence
// after a hardware reset.
func (b *rfm9xBase) initRegisters() error {
	version, err := b.readReg(rfm9xRegVersion)
	if err != nil {
		return err
	}
	if version != rfm9xExpectedVersion {
		return fmt.Errorf("rfm9x: version mismatch: expected 0x%02X, got 0x%02X", rfm9xExpectedVersion, version)
	}

	if err := b.enterLoRaSleep(); err != nil {
		return err
	}

	lna, err := b.readReg(rfm9xRegLna)
	if err != nil {
		return err
	}
	if b.lfBand {
		if err := b.writeReg(rfm9xRegLna, lna&0x3F); err != nil {
			return err
		}
	} else {
		if err := b.writeReg(rfm9xRegLna, 0x23); err != nil {
			return err
		}
	}

	mc3, err := b.readReg(rfm9xRegModemConfig3)
	if err != nil {
		return err
	}
	if err := b.writeReg(rfm9xRegModemConfig3, mc3|0x04); err != nil {
		return err
	}

	if err := b.writeReg(rfm9xRegFifoTxBase, 0x80); err != nil {
		return err
	}
	if err := b.writeReg(rfm9xRegFifoRxBase, 0x00); err != nil {
		return err
	}

	if err := b.setFrequency(b.frequencyHz); err != nil {
		return err
	}

	// Minimal-stage defaults: BW=125 kHz (code 7), CR 4/5 (code 1), SF7,
	// explicit header, CRC on, preamble length 8.
	if err := b.writeReg(rfm9xRegModemConfig1, (7<<4)|(1<<1)|0); err != nil {
		return err
	}
	if err := b.writeReg(rfm9xRegModemConfig2, (7<<4)|(1<<2)); err != nil {
		return err
	}
	if err := b.writeReg(rfm9xRegPreambleMsb, 0x00); err != nil {
		return err
	}
	if err := b.writeReg(rfm9xRegPreambleLsb, 0x08); err != nil {
		return err
	}

	if err := b.setTxPower(17, true); err != nil {
		return err
	}

	return b.standby()
}

// setFrequency writes the 24-bit Frf register from a frequency in Hz.
func (b *rfm9xBase) setFrequency(frequencyHz uint32) error {
	if frequencyHz < b.freqMinHz || frequencyHz > b.freqMaxHz {
		return fmt.Errorf("rfm9x: frequency_hz %d out of range [%d, %d]", frequencyHz, b.freqMinHz, b.freqMaxHz)
	}
	frf := uint32((uint64(frequencyHz) << 19) / rfm9xFXOSC)
	if err := b.writeReg(rfm9xRegFrfMsb, uint8((frf>>16)&0xFF)); err != nil {
		return err
	}
	if err := b.writeReg(rfm9xRegFrfMid, uint8((frf>>8)&0xFF)); err != nil {
		return err
	}
	if err := b.writeReg(rfm9xRegFrfLsb, uint8(frf&0xFF)); err != nil {
		return err
	}
	b.frequencyHz = frequencyHz
	return nil
}

// setTxPower sets RegPaConfig (and RegPaDac/RegOcp for the +20 dBm
// high-power case) from a target power in dBm.
func (b *rfm9xBase) setTxPower(powerDbm int, usePaBoost bool) error {
	if usePaBoost {
		if powerDbm > 17 {
			if powerDbm > 20 {
				powerDbm = 20
			}
			if err := b.writeReg(rfm9xRegPaDac, rfm9xPaDacHighPower); err != nil {
				return err
			}
			if err := b.writeReg(rfm9xRegOcp, rfm9xOcp240mA); err != nil {
				return err
			}
			return b.writeReg(rfm9xRegPaConfig, rfm9xPaBoost|0x0F)
		}
		if powerDbm < 2 {
			powerDbm = 2
		}
		if err := b.writeReg(rfm9xRegPaDac, rfm9xPaDacDefault); err != nil {
			return err
		}
		if err := b.writeReg(rfm9xRegOcp, rfm9xOcpDefault); err != nil {
			return err
		}
		return b.writeReg(rfm9xRegPaConfig, rfm9xPaBoost|uint8(powerDbm-2))
	}

	if err := b.writeReg(rfm9xRegPaDac, rfm9xPaDacDefault); err != nil {
		return err
	}
	if err := b.writeReg(rfm9xRegOcp, rfm9xOcpDefault); err != nil {
		return err
	}
	const maxPower = 7
	pmax := 10.8 + 0.6*float64(maxPower)
	outputPower := int(float64(powerDbm) - pmax + 15)
	if outputPower < 0 {
		outputPower = 0
	}
	if outputPower > 15 {
		outputPower = 15
	}
	return b.writeReg(rfm9xRegPaConfig, (maxPower<<4)|uint8(outputPower))
}

// configure validates and writes RegModemConfig1/2 (and, for SF6, the
// documented detection optimize/threshold registers and implicit-header
// payload length).
func (b *rfm9xBase) configure(sf uint8, bandwidthKHz float64, codingRate uint8, crc bool) error {
	bwCode, ok := rfm9xBwTable[bandwidthKHz]
	if !ok {
		return fmt.Errorf("rfm9x: bandwidth_khz %v is not one of the 10 supported values", bandwidthKHz)
	}
	if sf < 6 || sf > b.maxSF {
		return fmt.Errorf("rfm9x: sf %d out of range [6, %d]", sf, b.maxSF)
	}
	if sf == 6 && bandwidthKHz > 500.0 {
		return errors.New("rfm9x: SF6 only valid up to BW=500 kHz")
	}
	if codingRate < 5 || codingRate > 8 {
		return errors.New("rfm9x: coding_rate must be in [5, 8]")
	}

	if sf == 6 {
		if err := b.writeReg(rfm9xRegDetectionOpt, 0x05); err != nil {
			return err
		}
		if err := b.writeReg(rfm9xRegDetectionThr, 0x0C); err != nil {
			return err
		}
	} else {
		if err := b.writeReg(rfm9xRegDetectionOpt, 0x03); err != nil {
			return err
		}
		if err := b.writeReg(rfm9xRegDetectionThr, 0x0A); err != nil {
			return err
		}
	}

	var implicitHeaderBit uint8
	if sf == 6 {
		implicitHeaderBit = 1
	}
	if err := b.writeReg(rfm9xRegModemConfig1, (bwCode<<4)|((codingRate-4)<<1)|implicitHeaderBit); err != nil {
		return err
	}

	var crcBit uint8
	if crc {
		crcBit = 1
	}
	if err := b.writeReg(rfm9xRegModemConfig2, (sf<<4)|(crcBit<<2)|0x03); err != nil {
		return err
	}

	if sf == 6 {
		if err := b.writeReg(rfm9xRegPayloadLength, 255); err != nil {
			return err
		}
	}
	return nil
}

// standby enters STANDBY mode (crystal and baseband on, RF/PLL off, FIFO
// accessible).
func (b *rfm9xBase) standby() error {
	return b.writeReg(rfm9xRegOpMode, rfm9xModeLongRange|b.bandFlag()|rfm9xModeStandby)
}

// sleep enters SLEEP mode (lowest power; FIFO inaccessible).
func (b *rfm9xBase) sleep() error {
	return b.writeReg(rfm9xRegOpMode, rfm9xModeLongRange|b.bandFlag()|rfm9xModeSleep)
}

// version reads RegVersion.
func (b *rfm9xBase) version() (uint8, error) {
	return b.readReg(rfm9xRegVersion)
}

// rssi reads the current channel RSSI, converting RegRssiValue with the
// -137 dBm offset.
func (b *rfm9xBase) rssi() (float64, error) {
	raw, err := b.readReg(rfm9xRegRssi)
	if err != nil {
		return 0, err
	}
	return -137 + float64(raw), nil
}

// lastPacketRSSI reads the last packet's RSSI, converting RegPktRssiValue
// with the -137 dBm offset.
func (b *rfm9xBase) lastPacketRSSI() (float64, error) {
	raw, err := b.readReg(rfm9xRegPktRssi)
	if err != nil {
		return 0, err
	}
	return -137 + float64(raw), nil
}

// lastPacketSNR reads the last packet's SNR: RegPktSnrValue is a signed
// 8-bit value with 0.25 dB resolution.
func (b *rfm9xBase) lastPacketSNR() (float64, error) {
	raw, err := b.readReg(rfm9xRegPktSnr)
	if err != nil {
		return 0, err
	}
	return float64(int8(raw)) / 4.0, nil
}

// reset pulses NRESET (requires resetPin) and re-runs the init sequence.
func (b *rfm9xBase) reset() error {
	if b.resetPin == nil {
		return errors.New("rfm9x: reset requires resetPin")
	}
	if err := b.resetViaPin(); err != nil {
		return err
	}
	return b.initRegisters()
}

// readPayload reads one received packet out of the FIFO, positioning
// RegFifoAddrPtr at RegFifoRxCurrentAddr first (not RegFifoRxBaseAddr,
// since continuous mode may have wrapped the buffer).
func (b *rfm9xBase) readPayload() ([]byte, error) {
	current, err := b.readReg(rfm9xRegFifoRxCurrent)
	if err != nil {
		return nil, err
	}
	if err := b.writeReg(rfm9xRegFifoAddrPtr, current); err != nil {
		return nil, err
	}
	length, err := b.readReg(rfm9xRegRxNbBytes)
	if err != nil {
		return nil, err
	}
	return b.burstRead(rfm9xRegFifo, int(length))
}

// Send transmits data (max 255 bytes): STANDBY, fill the FIFO, TX, poll
// TxDone, then back to STANDBY.
func (b *rfm9xBase) Send(data []byte) error {
	if len(data) > 255 {
		return fmt.Errorf("rfm9x: payload length %d exceeds 255", len(data))
	}
	if err := b.standby(); err != nil {
		return err
	}
	if err := b.writeReg(rfm9xRegFifoAddrPtr, 0x80); err != nil {
		return err
	}
	if err := b.burstWrite(rfm9xRegFifo, data); err != nil {
		return err
	}
	if err := b.writeReg(rfm9xRegPayloadLength, uint8(len(data))); err != nil {
		return err
	}
	if err := b.writeReg(rfm9xRegDioMapping1, rfm9xDIO0TxDone); err != nil {
		return err
	}
	if err := b.writeReg(rfm9xRegOpMode, rfm9xModeLongRange|b.bandFlag()|rfm9xModeTx); err != nil {
		return err
	}

	for {
		irq, err := b.readReg(rfm9xRegIrqFlags)
		if err != nil {
			return err
		}
		if irq&rfm9xIrqTxDone != 0 {
			break
		}
		time.Sleep(2 * time.Millisecond)
	}
	if err := b.writeReg(rfm9xRegIrqFlags, rfm9xIrqTxDone); err != nil {
		return err
	}
	return b.standby()
}

// receivePolling implements RXSINGLE receive by polling RegIrqFlags.
func (b *rfm9xBase) receivePolling(timeoutMs int) ([]byte, error) {
	if err := b.standby(); err != nil {
		return nil, err
	}
	if err := b.writeReg(rfm9xRegDioMapping1, rfm9xDIO0RxDone); err != nil {
		return nil, err
	}
	if err := b.writeReg(rfm9xRegOpMode, rfm9xModeLongRange|b.bandFlag()|rfm9xModeRxSingle); err != nil {
		return nil, err
	}

	const stepMs = 5
	elapsed := 0
	for elapsed < timeoutMs {
		irq, err := b.readReg(rfm9xRegIrqFlags)
		if err != nil {
			return nil, err
		}
		if irq&rfm9xIrqRxDone != 0 {
			if err := b.writeReg(rfm9xRegIrqFlags, rfm9xIrqRxDone); err != nil {
				return nil, err
			}
			return b.readPayload()
		}
		if irq&rfm9xIrqRxTimeout != 0 {
			if err := b.writeReg(rfm9xRegIrqFlags, rfm9xIrqRxTimeout); err != nil {
				return nil, err
			}
			return nil, nil
		}
		time.Sleep(stepMs * time.Millisecond)
		elapsed += stepMs
	}
	if err := b.writeReg(rfm9xRegOpMode, rfm9xModeLongRange|b.bandFlag()|rfm9xModeStandby); err != nil {
		return nil, err
	}
	return nil, nil
}

// Receive is the Minimal-stage receive: RXSINGLE, poll RegIrqFlags for
// RxDone/RxTimeout, and return the payload (nil on timeout).
func (b *rfm9xBase) Receive(timeoutMs int) ([]byte, error) {
	return b.receivePolling(timeoutMs)
}

// receiveInterrupt implements RXSINGLE receive by waiting on a DIO0 rising
// edge instead of polling RegIrqFlags over SPI. Requires dio0Pin.
func (b *rfm9xBase) receiveInterrupt(timeoutMs int) ([]byte, error) {
	if b.dio0Pin == nil {
		return nil, errors.New("rfm9x: useInterrupt requires dio0Pin")
	}
	if err := b.standby(); err != nil {
		return nil, err
	}
	if err := b.writeReg(rfm9xRegDioMapping1, rfm9xDIO0RxDone); err != nil {
		return nil, err
	}
	if err := b.writeReg(rfm9xRegOpMode, rfm9xModeLongRange|b.bandFlag()|rfm9xModeRxSingle); err != nil {
		return nil, err
	}

	done := make(chan struct{}, 1)
	unsubscribe := b.dio0Pin.OnEdge(connection.Rising, func() {
		select {
		case done <- struct{}{}:
		default:
		}
	})
	defer unsubscribe()

	select {
	case <-done:
		irq, err := b.readReg(rfm9xRegIrqFlags)
		if err != nil {
			return nil, err
		}
		if err := b.writeReg(rfm9xRegIrqFlags, irq); err != nil {
			return nil, err
		}
		if irq&rfm9xIrqRxDone != 0 {
			return b.readPayload()
		}
		return nil, nil
	case <-time.After(time.Duration(timeoutMs) * time.Millisecond):
		if err := b.writeReg(rfm9xRegOpMode, rfm9xModeLongRange|b.bandFlag()|rfm9xModeStandby); err != nil {
			return nil, err
		}
		return nil, nil
	}
}

// receiveContinuous enters RXCONTINUOUS mode.
func (b *rfm9xBase) receiveContinuous() error {
	if err := b.standby(); err != nil {
		return err
	}
	if err := b.writeReg(rfm9xRegDioMapping1, rfm9xDIO0RxDone); err != nil {
		return err
	}
	if err := b.writeReg(rfm9xRegOpMode, rfm9xModeLongRange|b.bandFlag()|rfm9xModeRxCont); err != nil {
		return err
	}
	b.continuous = true
	return nil
}

// readPacket reads one buffered packet while in continuous receive mode,
// or returns a nil slice if RxDone has not fired.
func (b *rfm9xBase) readPacket() ([]byte, error) {
	irq, err := b.readReg(rfm9xRegIrqFlags)
	if err != nil {
		return nil, err
	}
	if irq&rfm9xIrqRxDone == 0 {
		return nil, nil
	}
	if err := b.writeReg(rfm9xRegIrqFlags, rfm9xIrqRxDone); err != nil {
		return nil, err
	}
	return b.readPayload()
}

// stopReceive returns to STANDBY from continuous receive mode.
func (b *rfm9xBase) stopReceive() error {
	if err := b.standby(); err != nil {
		return err
	}
	b.continuous = false
	return nil
}

// RFM95Minimal is the RFM95W LoRa transceiver driver — minimal interface.
// 868/915 MHz HF band, max spreading factor 12.
//
// Default configuration baked in at construction: SF7, 125 kHz bandwidth,
// coding rate 4/5, explicit header, CRC on, preamble length 8, TX power
// +17 dBm on PA_BOOST, AGC and (HF) LNA boost enabled.
type RFM95Minimal struct {
	rfm9xBase
}

func newRFM95Minimal(conn connection.Connection, frequencyHz uint32, resetPin connection.OutputPin, dio0Pin connection.InputPin) (*RFM95Minimal, error) {
	b, err := newRFM9xBase(conn, frequencyHz, rfm95FreqMinHz, rfm95FreqMaxHz, rfm95MaxSF, false, resetPin, dio0Pin)
	if err != nil {
		return nil, err
	}
	return &RFM95Minimal{rfm9xBase: *b}, nil
}

// NewRFM95Minimal creates a new RFM95Minimal, runs the LoRa init sequence,
// and validates RegVersion.
//
// conn must be a configured SPI connection (mode 0, ≤10 MHz, MSB first).
// frequencyHz is the carrier frequency in Hz (862,000,000-1,020,000,000).
func NewRFM95Minimal(conn connection.Connection, frequencyHz uint32) (*RFM95Minimal, error) {
	return newRFM95Minimal(conn, frequencyHz, nil, nil)
}

// RFM95Full is the RFM95W LoRa transceiver driver — full interface.
// Extends RFM95Minimal with hardware reset, complete LoRa configuration,
// TX power control, DIO0 interrupt-driven and continuous receive, and
// link-quality readouts.
type RFM95Full struct {
	*RFM95Minimal
}

// NewRFM95Full creates a new RFM95Full, runs the LoRa init sequence, and
// validates RegVersion.
//
// conn must be a configured SPI connection (mode 0, ≤10 MHz, MSB first).
// frequencyHz is the carrier frequency in Hz (862,000,000-1,020,000,000).
// resetPin and dio0Pin may be nil if NRESET/DIO0 are not wired: without
// resetPin, Reset returns an error; without dio0Pin, Receive with
// useInterrupt=true returns an error.
func NewRFM95Full(conn connection.Connection, frequencyHz uint32, resetPin connection.OutputPin, dio0Pin connection.InputPin) (*RFM95Full, error) {
	m, err := newRFM95Minimal(conn, frequencyHz, resetPin, dio0Pin)
	if err != nil {
		return nil, err
	}
	return &RFM95Full{RFM95Minimal: m}, nil
}

// Reset performs a hardware reset via resetPin and re-runs the LoRa init
// sequence.
func (f *RFM95Full) Reset() error { return f.reset() }

// Configure sets the LoRa modulation parameters: spreading factor 6-12
// (capped at 9 for RFM97W), bandwidth in kHz (one of 7.8, 10.4, 15.6,
// 20.8, 31.25, 41.7, 62.5, 125, 250, 500), coding rate denominator 5-8,
// and CRC on/off.
func (f *RFM95Full) Configure(sf uint8, bandwidthKHz float64, codingRate uint8, crc bool) error {
	return f.configure(sf, bandwidthKHz, codingRate, crc)
}

// SetFrequency changes the carrier frequency.
func (f *RFM95Full) SetFrequency(frequencyHz uint32) error { return f.setFrequency(frequencyHz) }

// SetTxPower sets the TX output power: -1 to +14 dBm on the RFO pin, or
// +2 to +20 dBm on PA_BOOST (+18 to +20 dBm enables the high-power PA_DAC
// setting automatically).
func (f *RFM95Full) SetTxPower(powerDbm int, usePaBoost bool) error {
	return f.setTxPower(powerDbm, usePaBoost)
}

// Receive receives a single packet: polls RegIrqFlags when useInterrupt is
// false, or waits on a DIO0 rising edge when true (requires dio0Pin).
// Returns a nil slice on timeout.
func (f *RFM95Full) Receive(timeoutMs int, useInterrupt bool) ([]byte, error) {
	if useInterrupt {
		return f.receiveInterrupt(timeoutMs)
	}
	return f.receivePolling(timeoutMs)
}

// ReceiveContinuous enters RXCONTINUOUS mode; subsequent ReadPacket calls
// drain buffered packets from the FIFO.
func (f *RFM95Full) ReceiveContinuous() error { return f.receiveContinuous() }

// ReadPacket reads one packet from the FIFO in continuous receive mode.
// Returns a nil slice if no packet is ready.
func (f *RFM95Full) ReadPacket() ([]byte, error) { return f.readPacket() }

// StopReceive returns to STANDBY from continuous receive mode.
func (f *RFM95Full) StopReceive() error { return f.stopReceive() }

// RSSI reads the current channel RSSI in dBm; readable during continuous
// receive.
func (f *RFM95Full) RSSI() (float64, error) { return f.rssi() }

// LastPacketRSSI reads the RSSI of the last received packet in dBm.
func (f *RFM95Full) LastPacketRSSI() (float64, error) { return f.lastPacketRSSI() }

// LastPacketSNR reads the SNR of the last received packet in dB.
func (f *RFM95Full) LastPacketSNR() (float64, error) { return f.lastPacketSNR() }

// Sleep enters SLEEP mode (lowest power; FIFO inaccessible).
func (f *RFM95Full) Sleep() error { return f.sleep() }

// Standby enters STANDBY mode (crystal and baseband on, RF/PLL off, FIFO
// accessible).
func (f *RFM95Full) Standby() error { return f.standby() }

// Version reads RegVersion. Expect 0x12 (SX1276 silicon).
func (f *RFM95Full) Version() (uint8, error) { return f.version() }

// RFM96Minimal is the RFM96W LoRa transceiver driver — minimal interface.
// 433/470 MHz LF band, max spreading factor 12.
//
// Default configuration baked in at construction: SF7, 125 kHz bandwidth,
// coding rate 4/5, explicit header, CRC on, preamble length 8, TX power
// +17 dBm on PA_BOOST, AGC enabled.
type RFM96Minimal struct {
	rfm9xBase
}

func newRFM96Minimal(conn connection.Connection, frequencyHz uint32, resetPin connection.OutputPin, dio0Pin connection.InputPin) (*RFM96Minimal, error) {
	b, err := newRFM9xBase(conn, frequencyHz, rfm96FreqMinHz, rfm96FreqMaxHz, rfm96MaxSF, true, resetPin, dio0Pin)
	if err != nil {
		return nil, err
	}
	return &RFM96Minimal{rfm9xBase: *b}, nil
}

// NewRFM96Minimal creates a new RFM96Minimal, runs the LoRa init sequence,
// and validates RegVersion.
//
// conn must be a configured SPI connection (mode 0, ≤10 MHz, MSB first).
// frequencyHz is the carrier frequency in Hz (410,000,000-525,000,000).
func NewRFM96Minimal(conn connection.Connection, frequencyHz uint32) (*RFM96Minimal, error) {
	return newRFM96Minimal(conn, frequencyHz, nil, nil)
}

// RFM96Full is the RFM96W LoRa transceiver driver — full interface.
// Extends RFM96Minimal with hardware reset, complete LoRa configuration,
// TX power control, DIO0 interrupt-driven and continuous receive, and
// link-quality readouts.
type RFM96Full struct {
	*RFM96Minimal
}

// NewRFM96Full creates a new RFM96Full, runs the LoRa init sequence, and
// validates RegVersion.
//
// conn must be a configured SPI connection (mode 0, ≤10 MHz, MSB first).
// frequencyHz is the carrier frequency in Hz (410,000,000-525,000,000).
// resetPin and dio0Pin may be nil if NRESET/DIO0 are not wired: without
// resetPin, Reset returns an error; without dio0Pin, Receive with
// useInterrupt=true returns an error.
func NewRFM96Full(conn connection.Connection, frequencyHz uint32, resetPin connection.OutputPin, dio0Pin connection.InputPin) (*RFM96Full, error) {
	m, err := newRFM96Minimal(conn, frequencyHz, resetPin, dio0Pin)
	if err != nil {
		return nil, err
	}
	return &RFM96Full{RFM96Minimal: m}, nil
}

// Reset performs a hardware reset via resetPin and re-runs the LoRa init
// sequence.
func (f *RFM96Full) Reset() error { return f.reset() }

// Configure sets the LoRa modulation parameters: spreading factor 6-12,
// bandwidth in kHz (one of 7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125,
// 250, 500 — 250/500 are not valid on the 169 MHz sub-band), coding rate
// denominator 5-8, and CRC on/off.
func (f *RFM96Full) Configure(sf uint8, bandwidthKHz float64, codingRate uint8, crc bool) error {
	return f.configure(sf, bandwidthKHz, codingRate, crc)
}

// SetFrequency changes the carrier frequency.
func (f *RFM96Full) SetFrequency(frequencyHz uint32) error { return f.setFrequency(frequencyHz) }

// SetTxPower sets the TX output power: -1 to +14 dBm on the RFO pin, or
// +2 to +20 dBm on PA_BOOST (+18 to +20 dBm enables the high-power PA_DAC
// setting automatically).
func (f *RFM96Full) SetTxPower(powerDbm int, usePaBoost bool) error {
	return f.setTxPower(powerDbm, usePaBoost)
}

// Receive receives a single packet: polls RegIrqFlags when useInterrupt is
// false, or waits on a DIO0 rising edge when true (requires dio0Pin).
// Returns a nil slice on timeout.
func (f *RFM96Full) Receive(timeoutMs int, useInterrupt bool) ([]byte, error) {
	if useInterrupt {
		return f.receiveInterrupt(timeoutMs)
	}
	return f.receivePolling(timeoutMs)
}

// ReceiveContinuous enters RXCONTINUOUS mode; subsequent ReadPacket calls
// drain buffered packets from the FIFO.
func (f *RFM96Full) ReceiveContinuous() error { return f.receiveContinuous() }

// ReadPacket reads one packet from the FIFO in continuous receive mode.
// Returns a nil slice if no packet is ready.
func (f *RFM96Full) ReadPacket() ([]byte, error) { return f.readPacket() }

// StopReceive returns to STANDBY from continuous receive mode.
func (f *RFM96Full) StopReceive() error { return f.stopReceive() }

// RSSI reads the current channel RSSI in dBm; readable during continuous
// receive.
func (f *RFM96Full) RSSI() (float64, error) { return f.rssi() }

// LastPacketRSSI reads the RSSI of the last received packet in dBm.
func (f *RFM96Full) LastPacketRSSI() (float64, error) { return f.lastPacketRSSI() }

// LastPacketSNR reads the SNR of the last received packet in dB.
func (f *RFM96Full) LastPacketSNR() (float64, error) { return f.lastPacketSNR() }

// Sleep enters SLEEP mode (lowest power; FIFO inaccessible).
func (f *RFM96Full) Sleep() error { return f.sleep() }

// Standby enters STANDBY mode (crystal and baseband on, RF/PLL off, FIFO
// accessible).
func (f *RFM96Full) Standby() error { return f.standby() }

// Version reads RegVersion. Expect 0x12 (SX1276 silicon).
func (f *RFM96Full) Version() (uint8, error) { return f.version() }

// RFM97Minimal is the RFM97W LoRa transceiver driver — minimal interface.
// 868/915 MHz HF band, max spreading factor 9 (lower than the other three
// variants).
//
// Default configuration baked in at construction: SF7, 125 kHz bandwidth,
// coding rate 4/5, explicit header, CRC on, preamble length 8, TX power
// +17 dBm on PA_BOOST, AGC and LNA boost enabled.
type RFM97Minimal struct {
	rfm9xBase
}

func newRFM97Minimal(conn connection.Connection, frequencyHz uint32, resetPin connection.OutputPin, dio0Pin connection.InputPin) (*RFM97Minimal, error) {
	b, err := newRFM9xBase(conn, frequencyHz, rfm97FreqMinHz, rfm97FreqMaxHz, rfm97MaxSF, false, resetPin, dio0Pin)
	if err != nil {
		return nil, err
	}
	return &RFM97Minimal{rfm9xBase: *b}, nil
}

// NewRFM97Minimal creates a new RFM97Minimal, runs the LoRa init sequence,
// and validates RegVersion.
//
// conn must be a configured SPI connection (mode 0, ≤10 MHz, MSB first).
// frequencyHz is the carrier frequency in Hz (862,000,000-1,020,000,000).
func NewRFM97Minimal(conn connection.Connection, frequencyHz uint32) (*RFM97Minimal, error) {
	return newRFM97Minimal(conn, frequencyHz, nil, nil)
}

// RFM97Full is the RFM97W LoRa transceiver driver — full interface.
// Extends RFM97Minimal with hardware reset, complete LoRa configuration,
// TX power control, DIO0 interrupt-driven and continuous receive, and
// link-quality readouts.
type RFM97Full struct {
	*RFM97Minimal
}

// NewRFM97Full creates a new RFM97Full, runs the LoRa init sequence, and
// validates RegVersion.
//
// conn must be a configured SPI connection (mode 0, ≤10 MHz, MSB first).
// frequencyHz is the carrier frequency in Hz (862,000,000-1,020,000,000).
// resetPin and dio0Pin may be nil if NRESET/DIO0 are not wired: without
// resetPin, Reset returns an error; without dio0Pin, Receive with
// useInterrupt=true returns an error.
func NewRFM97Full(conn connection.Connection, frequencyHz uint32, resetPin connection.OutputPin, dio0Pin connection.InputPin) (*RFM97Full, error) {
	m, err := newRFM97Minimal(conn, frequencyHz, resetPin, dio0Pin)
	if err != nil {
		return nil, err
	}
	return &RFM97Full{RFM97Minimal: m}, nil
}

// Reset performs a hardware reset via resetPin and re-runs the LoRa init
// sequence.
func (f *RFM97Full) Reset() error { return f.reset() }

// Configure sets the LoRa modulation parameters: spreading factor 6-9
// (RFM97W's reduced maximum), bandwidth in kHz (one of 7.8, 10.4, 15.6,
// 20.8, 31.25, 41.7, 62.5, 125, 250, 500), coding rate denominator 5-8,
// and CRC on/off.
func (f *RFM97Full) Configure(sf uint8, bandwidthKHz float64, codingRate uint8, crc bool) error {
	return f.configure(sf, bandwidthKHz, codingRate, crc)
}

// SetFrequency changes the carrier frequency.
func (f *RFM97Full) SetFrequency(frequencyHz uint32) error { return f.setFrequency(frequencyHz) }

// SetTxPower sets the TX output power: -1 to +14 dBm on the RFO pin, or
// +2 to +20 dBm on PA_BOOST (+18 to +20 dBm enables the high-power PA_DAC
// setting automatically).
func (f *RFM97Full) SetTxPower(powerDbm int, usePaBoost bool) error {
	return f.setTxPower(powerDbm, usePaBoost)
}

// Receive receives a single packet: polls RegIrqFlags when useInterrupt is
// false, or waits on a DIO0 rising edge when true (requires dio0Pin).
// Returns a nil slice on timeout.
func (f *RFM97Full) Receive(timeoutMs int, useInterrupt bool) ([]byte, error) {
	if useInterrupt {
		return f.receiveInterrupt(timeoutMs)
	}
	return f.receivePolling(timeoutMs)
}

// ReceiveContinuous enters RXCONTINUOUS mode; subsequent ReadPacket calls
// drain buffered packets from the FIFO.
func (f *RFM97Full) ReceiveContinuous() error { return f.receiveContinuous() }

// ReadPacket reads one packet from the FIFO in continuous receive mode.
// Returns a nil slice if no packet is ready.
func (f *RFM97Full) ReadPacket() ([]byte, error) { return f.readPacket() }

// StopReceive returns to STANDBY from continuous receive mode.
func (f *RFM97Full) StopReceive() error { return f.stopReceive() }

// RSSI reads the current channel RSSI in dBm; readable during continuous
// receive.
func (f *RFM97Full) RSSI() (float64, error) { return f.rssi() }

// LastPacketRSSI reads the RSSI of the last received packet in dBm.
func (f *RFM97Full) LastPacketRSSI() (float64, error) { return f.lastPacketRSSI() }

// LastPacketSNR reads the SNR of the last received packet in dB.
func (f *RFM97Full) LastPacketSNR() (float64, error) { return f.lastPacketSNR() }

// Sleep enters SLEEP mode (lowest power; FIFO inaccessible).
func (f *RFM97Full) Sleep() error { return f.sleep() }

// Standby enters STANDBY mode (crystal and baseband on, RF/PLL off, FIFO
// accessible).
func (f *RFM97Full) Standby() error { return f.standby() }

// Version reads RegVersion. Expect 0x12 (SX1276 silicon).
func (f *RFM97Full) Version() (uint8, error) { return f.version() }

// RFM98Minimal is the RFM98W LoRa transceiver driver — minimal interface.
// 433/470 MHz LF band, max spreading factor 12.
//
// Default configuration baked in at construction: SF7, 125 kHz bandwidth,
// coding rate 4/5, explicit header, CRC on, preamble length 8, TX power
// +17 dBm on PA_BOOST, AGC enabled.
type RFM98Minimal struct {
	rfm9xBase
}

func newRFM98Minimal(conn connection.Connection, frequencyHz uint32, resetPin connection.OutputPin, dio0Pin connection.InputPin) (*RFM98Minimal, error) {
	b, err := newRFM9xBase(conn, frequencyHz, rfm98FreqMinHz, rfm98FreqMaxHz, rfm98MaxSF, true, resetPin, dio0Pin)
	if err != nil {
		return nil, err
	}
	return &RFM98Minimal{rfm9xBase: *b}, nil
}

// NewRFM98Minimal creates a new RFM98Minimal, runs the LoRa init sequence,
// and validates RegVersion.
//
// conn must be a configured SPI connection (mode 0, ≤10 MHz, MSB first).
// frequencyHz is the carrier frequency in Hz (410,000,000-525,000,000).
func NewRFM98Minimal(conn connection.Connection, frequencyHz uint32) (*RFM98Minimal, error) {
	return newRFM98Minimal(conn, frequencyHz, nil, nil)
}

// RFM98Full is the RFM98W LoRa transceiver driver — full interface.
// Extends RFM98Minimal with hardware reset, complete LoRa configuration,
// TX power control, DIO0 interrupt-driven and continuous receive, and
// link-quality readouts.
type RFM98Full struct {
	*RFM98Minimal
}

// NewRFM98Full creates a new RFM98Full, runs the LoRa init sequence, and
// validates RegVersion.
//
// conn must be a configured SPI connection (mode 0, ≤10 MHz, MSB first).
// frequencyHz is the carrier frequency in Hz (410,000,000-525,000,000).
// resetPin and dio0Pin may be nil if NRESET/DIO0 are not wired: without
// resetPin, Reset returns an error; without dio0Pin, Receive with
// useInterrupt=true returns an error.
func NewRFM98Full(conn connection.Connection, frequencyHz uint32, resetPin connection.OutputPin, dio0Pin connection.InputPin) (*RFM98Full, error) {
	m, err := newRFM98Minimal(conn, frequencyHz, resetPin, dio0Pin)
	if err != nil {
		return nil, err
	}
	return &RFM98Full{RFM98Minimal: m}, nil
}

// Reset performs a hardware reset via resetPin and re-runs the LoRa init
// sequence.
func (f *RFM98Full) Reset() error { return f.reset() }

// Configure sets the LoRa modulation parameters: spreading factor 6-12,
// bandwidth in kHz (one of 7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125,
// 250, 500 — 250/500 are not valid on the 169 MHz sub-band), coding rate
// denominator 5-8, and CRC on/off.
func (f *RFM98Full) Configure(sf uint8, bandwidthKHz float64, codingRate uint8, crc bool) error {
	return f.configure(sf, bandwidthKHz, codingRate, crc)
}

// SetFrequency changes the carrier frequency.
func (f *RFM98Full) SetFrequency(frequencyHz uint32) error { return f.setFrequency(frequencyHz) }

// SetTxPower sets the TX output power: -1 to +14 dBm on the RFO pin, or
// +2 to +20 dBm on PA_BOOST (+18 to +20 dBm enables the high-power PA_DAC
// setting automatically).
func (f *RFM98Full) SetTxPower(powerDbm int, usePaBoost bool) error {
	return f.setTxPower(powerDbm, usePaBoost)
}

// Receive receives a single packet: polls RegIrqFlags when useInterrupt is
// false, or waits on a DIO0 rising edge when true (requires dio0Pin).
// Returns a nil slice on timeout.
func (f *RFM98Full) Receive(timeoutMs int, useInterrupt bool) ([]byte, error) {
	if useInterrupt {
		return f.receiveInterrupt(timeoutMs)
	}
	return f.receivePolling(timeoutMs)
}

// ReceiveContinuous enters RXCONTINUOUS mode; subsequent ReadPacket calls
// drain buffered packets from the FIFO.
func (f *RFM98Full) ReceiveContinuous() error { return f.receiveContinuous() }

// ReadPacket reads one packet from the FIFO in continuous receive mode.
// Returns a nil slice if no packet is ready.
func (f *RFM98Full) ReadPacket() ([]byte, error) { return f.readPacket() }

// StopReceive returns to STANDBY from continuous receive mode.
func (f *RFM98Full) StopReceive() error { return f.stopReceive() }

// RSSI reads the current channel RSSI in dBm; readable during continuous
// receive.
func (f *RFM98Full) RSSI() (float64, error) { return f.rssi() }

// LastPacketRSSI reads the RSSI of the last received packet in dBm.
func (f *RFM98Full) LastPacketRSSI() (float64, error) { return f.lastPacketRSSI() }

// LastPacketSNR reads the SNR of the last received packet in dB.
func (f *RFM98Full) LastPacketSNR() (float64, error) { return f.lastPacketSNR() }

// Sleep enters SLEEP mode (lowest power; FIFO inaccessible).
func (f *RFM98Full) Sleep() error { return f.sleep() }

// Standby enters STANDBY mode (crystal and baseband on, RF/PLL off, FIFO
// accessible).
func (f *RFM98Full) Standby() error { return f.standby() }

// Version reads RegVersion. Expect 0x12 (SX1276 silicon).
func (f *RFM98Full) Version() (uint8, error) { return f.version() }
