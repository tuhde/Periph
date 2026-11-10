package adcdac

import (
	"errors"
	"fmt"
	"math"
	"strings"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// MCP4XXXPartInfo is one row of the MCP4XXX family descriptor table.
type MCP4XXXPartInfo struct {
	Name          string // part name, e.g. "MCP4532"
	Channels      int    // number of resistor networks (1 or 2)
	MaxCode       int    // maximum wiper code: 128 (7-bit) or 256 (8-bit)
	Potentiometer bool   // true for the X1 parts (terminal A on a pin)
	Nonvolatile   bool   // true for the 454X / 456X / 464X / 466X parts (EEPROM)
	WPPin         bool   // true for the MCP4641 / MCP4661 (hardware WP pin)
	AddrMin       uint8  // lowest 7-bit I²C address (all address pins low)
	AddrMax       uint8  // highest 7-bit I²C address
}

var mcp4xxxParts = []MCP4XXXPartInfo{
	{"MCP4531", 1, 128, true, false, false, 0x2E, 0x2F},
	{"MCP4532", 1, 128, false, false, false, 0x58, 0x5B},
	{"MCP4551", 1, 256, true, false, false, 0x2E, 0x2F},
	{"MCP4552", 1, 256, false, false, false, 0x58, 0x5B},
	{"MCP4631", 2, 128, true, false, false, 0x28, 0x2F},
	{"MCP4632", 2, 128, false, false, false, 0x58, 0x5B},
	{"MCP4651", 2, 256, true, false, false, 0x28, 0x2F},
	{"MCP4652", 2, 256, false, false, false, 0x58, 0x5B},
	{"MCP4541", 1, 128, true, true, false, 0x2E, 0x2F},
	{"MCP4542", 1, 128, false, true, false, 0x58, 0x5B},
	{"MCP4561", 1, 256, true, true, false, 0x2E, 0x2F},
	{"MCP4562", 1, 256, false, true, false, 0x58, 0x5B},
	{"MCP4641", 2, 128, true, true, true, 0x28, 0x2F},
	{"MCP4642", 2, 128, false, true, false, 0x58, 0x5B},
	{"MCP4661", 2, 256, true, true, true, 0x28, 0x2F},
	{"MCP4662", 2, 256, false, true, false, 0x58, 0x5B},
}

// MCP4XXXParts returns the descriptor table of all sixteen supported parts.
func MCP4XXXParts() []MCP4XXXPartInfo {
	return append([]MCP4XXXPartInfo(nil), mcp4xxxParts...)
}

// MCP4XXXLookup returns the descriptor of a part by name (case-insensitive).
func MCP4XXXLookup(part string) (MCP4XXXPartInfo, error) {
	for _, p := range mcp4xxxParts {
		if strings.EqualFold(p.Name, part) {
			return p, nil
		}
	}
	return MCP4XXXPartInfo{}, fmt.Errorf("mcp4xxx: unknown part %q", part)
}

// MCP4XXXDefaultAddress returns the default 7-bit I²C address (all address pins low) of
// a part: 0x2E (45X1), 0x58 (45X2 / 46X2) or 0x28 (46X1).
func MCP4XXXDefaultAddress(part string) (uint8, error) {
	p, err := MCP4XXXLookup(part)
	return p.AddrMin, err
}

// Errors returned by the MCP4XXX driver.
var (
	// ErrMCP4XXXUnsupported means the operation does not exist on the selected part
	// (EEPROM on a volatile part, W–A on a rheostat, no hvc pin, set_both on a single network).
	ErrMCP4XXXUnsupported = errors.New("mcp4xxx: operation not supported by this part")
	// ErrMCP4XXXTimeout means the EEPROM write cycle did not finish in time.
	ErrMCP4XXXTimeout = errors.New("mcp4xxx: EEPROM write did not finish")
)

const (
	mcp4xxxAddrWiper0   = 0x00
	mcp4xxxAddrNVWiper0 = 0x02
	mcp4xxxAddrTCON     = 0x04
	mcp4xxxAddrStatus   = 0x05
	mcp4xxxAddrData0    = 0x06
	mcp4xxxAddrWP       = 0x0F

	mcp4xxxOpWrite = 0x00
	mcp4xxxOpInc   = 0x04
	mcp4xxxOpDec   = 0x08
	mcp4xxxOpRead  = 0x0C

	mcp4xxxTCONDefault = 0x1FF
	mcp4xxxTCONGCEN    = 8

	mcp4xxxStatusEEWA = 0x08
	mcp4xxxStatusWP   = 0x01
)

// Per-channel TCON bit positions: HW, A, W, B.
var mcp4xxxTCONBits = [2][4]uint{{3, 2, 1, 0}, {7, 6, 5, 4}}

var mcp4xxxStatusWL = [2]int{0x02, 0x04}

// MCP4XXXStatus is the decoded STATUS register of the non-volatile parts.
type MCP4XXXStatus struct {
	EEPROMBusy   bool    // an EEPROM write cycle is in progress (EEWA)
	WiperLock    [2]bool // WiperLock of network 0 and 1
	WriteProtect bool    // hardware WP pin OR the nonvolatile write-protect bit
}

// MCP4XXXMinimal is the MCP453X–MCP466X family driver — minimal interface.
//
// One driver for all sixteen parts; a part descriptor selects resolution, networks, pot /
// rheostat pinout, memory type and address range. The command byte
// AD3 AD2 AD1 AD0 C1 C0 D9 D8 plays the role of the register address of the
// RegisterConnection; a read returns two bytes. Defaults: RAB = 10 kΩ, RW = 75 Ω; the wipers
// keep their power-up value until written. Construction performs no I²C traffic (Init does).
type MCP4XXXMinimal struct {
	connection connection.RegisterConnection
	desc       MCP4XXXPartInfo
	rAB        float64
	rWiper     float64
}

// NewMCP4XXXMinimal creates an MCP4XXXMinimal; no I²C traffic. part is a name such as
// "MCP4532" (case-insensitive; an unknown part returns an error), rAB the end-to-end
// resistance in ohms (5000, 10000, 50000 or 100000) and rWiper the wiper resistance in ohms
// (typically 75). The connection must be bound to an address in the part's range.
func NewMCP4XXXMinimal(t connection.RegisterConnection, part string, rAB, rWiper float64) (*MCP4XXXMinimal, error) {
	desc, err := MCP4XXXLookup(part)
	if err != nil {
		return nil, err
	}
	return &MCP4XXXMinimal{connection: t, desc: desc, rAB: rAB, rWiper: rWiper}, nil
}

// Init is the bus check: it reads the wiper register(s), TCON (and STATUS on non-volatile parts); no writes.
func (d *MCP4XXXMinimal) Init() error {
	for c := 0; c < d.desc.Channels; c++ {
		if _, err := d.readWiper(c); err != nil {
			return err
		}
	}
	if _, err := d.read9(mcp4xxxAddrTCON); err != nil {
		return err
	}
	if d.desc.Nonvolatile {
		if _, err := d.read9(mcp4xxxAddrStatus); err != nil {
			return err
		}
	}
	return nil
}

func (d *MCP4XXXMinimal) checkChannel(channel int) error {
	if channel < 0 || channel >= d.desc.Channels {
		return fmt.Errorf("mcp4xxx: channel %d out of range for %s", channel, d.desc.Name)
	}
	return nil
}

func (d *MCP4XXXMinimal) read9(address int) (int, error) {
	buf, err := d.connection.ReadReg(uint32(address<<4|mcp4xxxOpRead), 2)
	if err != nil {
		return 0, err
	}
	return int(buf[0]&0x01)<<8 | int(buf[1]), nil
}

func (d *MCP4XXXMinimal) write9(address, value int) error {
	command := address<<4 | mcp4xxxOpWrite | (value>>8)&0x01
	return d.connection.WriteReg(uint32(command), []byte{byte(value)})
}

func (d *MCP4XXXMinimal) readWiper(channel int) (int, error) {
	v, err := d.read9(mcp4xxxAddrWiper0 + channel)
	if v > d.desc.MaxCode {
		v = d.desc.MaxCode
	}
	return v, err
}

func (d *MCP4XXXMinimal) clamp(code int) int {
	if code < 0 {
		return 0
	}
	if code > d.desc.MaxCode {
		return d.desc.MaxCode
	}
	return code
}

// SetWiper sets the volatile wiper of a network; code is clamped to 0..max code (128 / 256).
// A network locked by WiperLock ignores the write.
func (d *MCP4XXXMinimal) SetWiper(code, channel int) error {
	if err := d.checkChannel(channel); err != nil {
		return err
	}
	return d.write9(mcp4xxxAddrWiper0+channel, d.clamp(code))
}

// GetWiper reads the volatile wiper of a network (0..max code).
func (d *MCP4XXXMinimal) GetWiper(channel int) (int, error) {
	if err := d.checkChannel(channel); err != nil {
		return 0, err
	}
	return d.readWiper(channel)
}

// SetResistance sets the W–B resistance (ohms) to the nearest reachable value.
func (d *MCP4XXXMinimal) SetResistance(ohms float64, channel int) error {
	if err := d.checkChannel(channel); err != nil {
		return err
	}
	return d.SetWiper(int(math.Round((ohms-d.rWiper)/d.rAB*float64(d.desc.MaxCode))), channel)
}

// GetResistance reads the W–B resistance in ohms: N / max code × RAB + RW.
func (d *MCP4XXXMinimal) GetResistance(channel int) (float64, error) {
	code, err := d.GetWiper(channel)
	if err != nil {
		return 0, err
	}
	return float64(code)/float64(d.desc.MaxCode)*d.rAB + d.rWiper, nil
}

// MCP4XXXFull extends MCP4XXXMinimal with ratio helpers, increment / decrement, shutdown and
// terminal control, General Call, reset, raw access and — on the non-volatile parts only
// (marked NV; they return ErrMCP4XXXUnsupported on the volatile parts) — the EEPROM wiper,
// general-purpose EEPROM registers, STATUS, WiperLock and write protect.
type MCP4XXXFull struct {
	MCP4XXXMinimal
	hvc         connection.OutputPin
	hvcSettleMs int
}

// NewMCP4XXXFull creates an MCP4XXXFull; no I²C traffic. hvc is an optional OutputPin (nil
// if unused) switching the external circuit that drives HVC/A0 to 8.5–12.5 V, needed only
// by SetWiperLock / SetWriteProtect (ignored on volatile parts); hvcSettleMs is the delay
// between asserting hvc and the first SCL edge (the datasheet gives no value; 1 ms).
func NewMCP4XXXFull(t connection.RegisterConnection, part string, rAB, rWiper float64, hvc connection.OutputPin, hvcSettleMs int) (*MCP4XXXFull, error) {
	m, err := NewMCP4XXXMinimal(t, part, rAB, rWiper)
	if err != nil {
		return nil, err
	}
	f := &MCP4XXXFull{MCP4XXXMinimal: *m, hvcSettleMs: hvcSettleMs}
	if m.desc.Nonvolatile {
		f.hvc = hvc
	}
	return f, nil
}

func (d *MCP4XXXFull) requireNV() error {
	if !d.desc.Nonvolatile {
		return ErrMCP4XXXUnsupported
	}
	return nil
}

func (d *MCP4XXXFull) requirePot() error {
	if !d.desc.Potentiometer {
		return ErrMCP4XXXUnsupported
	}
	return nil
}

// Channels returns the number of resistor networks (1 or 2).
func (d *MCP4XXXFull) Channels() int { return d.desc.Channels }

// Steps returns the number of wiper positions (129 or 257).
func (d *MCP4XXXFull) Steps() int { return d.desc.MaxCode + 1 }

// IsPotentiometer reports whether the part is an X1 potentiometer (terminal A on a pin).
func (d *MCP4XXXFull) IsPotentiometer() bool { return d.desc.Potentiometer }

// IsNonvolatile reports whether the part has EEPROM (454X / 456X / 464X / 466X).
func (d *MCP4XXXFull) IsNonvolatile() bool { return d.desc.Nonvolatile }

// SetRatio sets the wiper to round(ratio × max code); ratio is clamped to 0.0–1.0.
func (d *MCP4XXXFull) SetRatio(ratio float64, channel int) error {
	ratio = math.Max(0, math.Min(1, ratio))
	return d.SetWiper(int(math.Round(ratio*float64(d.desc.MaxCode))), channel)
}

// GetRatio reads the wiper position as a ratio: code / max code.
func (d *MCP4XXXFull) GetRatio(channel int) (float64, error) {
	code, err := d.GetWiper(channel)
	if err != nil {
		return 0, err
	}
	return float64(code) / float64(d.desc.MaxCode), nil
}

// GetResistanceWA reads the W–A resistance, (max code − N) / max code × RAB + RW
// (potentiometer parts only).
func (d *MCP4XXXFull) GetResistanceWA(channel int) (float64, error) {
	if err := d.requirePot(); err != nil {
		return 0, err
	}
	code, err := d.GetWiper(channel)
	if err != nil {
		return 0, err
	}
	max := float64(d.desc.MaxCode)
	return (max-float64(code))/max*d.rAB + d.rWiper, nil
}

// SetResistanceWA sets the W–A resistance to the nearest reachable value (potentiometer parts only).
func (d *MCP4XXXFull) SetResistanceWA(ohms float64, channel int) error {
	if err := d.requirePot(); err != nil {
		return err
	}
	if err := d.checkChannel(channel); err != nil {
		return err
	}
	n := int(math.Round((ohms - d.rWiper) / d.rAB * float64(d.desc.MaxCode)))
	return d.SetWiper(d.desc.MaxCode-n, channel)
}

// GetResistanceStep returns the resistance of one step, RAB / max code, in ohms.
func (d *MCP4XXXFull) GetResistanceStep() float64 { return d.rAB / float64(d.desc.MaxCode) }

// SetBoth writes both wipers of a dual part (two transactions); single parts return ErrMCP4XXXUnsupported.
func (d *MCP4XXXFull) SetBoth(code0, code1 int) error {
	if d.desc.Channels < 2 {
		return ErrMCP4XXXUnsupported
	}
	if err := d.SetWiper(code0, 0); err != nil {
		return err
	}
	return d.SetWiper(code1, 1)
}

func (d *MCP4XXXFull) step(op, steps, channel int) (int, error) {
	if err := d.checkChannel(channel); err != nil {
		return 0, err
	}
	command := uint32((mcp4xxxAddrWiper0+channel)<<4 | op)
	if steps > 0 {
		// Continuous mode: one command byte per step, no data bytes, one transaction.
		extra := make([]byte, steps-1)
		for i := range extra {
			extra[i] = byte(command)
		}
		if err := d.connection.WriteReg(command, extra); err != nil {
			return 0, err
		}
	}
	return d.readWiper(channel)
}

// Increment increments the wiper by steps in one continuous transaction (saturating at the
// max code) and returns the new code (read back).
func (d *MCP4XXXFull) Increment(steps, channel int) (int, error) {
	return d.step(mcp4xxxOpInc, steps, channel)
}

// Decrement decrements the wiper by steps in one continuous transaction (saturating at 0) and
// returns the new code (read back).
func (d *MCP4XXXFull) Decrement(steps, channel int) (int, error) {
	return d.step(mcp4xxxOpDec, steps, channel)
}

func (d *MCP4XXXFull) modifyTCON(bits map[uint]bool) error {
	value, err := d.read9(mcp4xxxAddrTCON)
	if err != nil {
		return err
	}
	for bit, on := range bits {
		if on {
			value |= 1 << bit
		} else {
			value &^= 1 << bit
		}
	}
	return d.write9(mcp4xxxAddrTCON, value&0x1FF)
}

// Shutdown shuts a network down (TCON.RxHW = 0): A disconnected, W connected to B; the wiper is kept.
func (d *MCP4XXXFull) Shutdown(channel int) error {
	if err := d.checkChannel(channel); err != nil {
		return err
	}
	return d.modifyTCON(map[uint]bool{mcp4xxxTCONBits[channel][0]: false})
}

// Resume leaves shutdown (TCON.RxHW = 1).
func (d *MCP4XXXFull) Resume(channel int) error {
	if err := d.checkChannel(channel); err != nil {
		return err
	}
	return d.modifyTCON(map[uint]bool{mcp4xxxTCONBits[channel][0]: true})
}

// IsShutdown reports whether the network is in shutdown (read from TCON).
func (d *MCP4XXXFull) IsShutdown(channel int) (bool, error) {
	if err := d.checkChannel(channel); err != nil {
		return false, err
	}
	v, err := d.read9(mcp4xxxAddrTCON)
	return (v>>mcp4xxxTCONBits[channel][0])&1 == 0, err
}

// SetTerminals connects or disconnects the A, W and B terminals of a network (TCON.RxA / RxW / RxB).
func (d *MCP4XXXFull) SetTerminals(a, w, b bool, channel int) error {
	if err := d.checkChannel(channel); err != nil {
		return err
	}
	bits := mcp4xxxTCONBits[channel]
	return d.modifyTCON(map[uint]bool{bits[1]: a, bits[2]: w, bits[3]: b})
}

// GetTerminals reads the (a, w, b) terminal connection state of a network from TCON.
func (d *MCP4XXXFull) GetTerminals(channel int) (a, w, b bool, err error) {
	if err = d.checkChannel(channel); err != nil {
		return
	}
	v, err := d.read9(mcp4xxxAddrTCON)
	if err != nil {
		return
	}
	bits := mcp4xxxTCONBits[channel]
	return (v>>bits[1])&1 == 1, (v>>bits[2])&1 == 1, (v>>bits[3])&1 == 1, nil
}

// GetTCON reads the raw 9-bit TCON register.
func (d *MCP4XXXFull) GetTCON() (int, error) { return d.read9(mcp4xxxAddrTCON) }

// SetGeneralCall enables or disables the General Call address (TCON.GCEN).
func (d *MCP4XXXFull) SetGeneralCall(enabled bool) error {
	return d.modifyTCON(map[uint]bool{mcp4xxxTCONGCEN: enabled})
}

// Reset emulates a power-on reset: it writes every volatile wiper with its power-up value
// (mid-scale, or the EEPROM wiper on non-volatile parts) and TCON 0x1FF; networks locked by
// WiperLock are skipped.
func (d *MCP4XXXFull) Reset() error {
	var locked [2]bool
	if d.desc.Nonvolatile {
		st, err := d.ReadStatus()
		if err != nil {
			return err
		}
		locked = st.WiperLock
	}
	anyLocked := false
	for c := 0; c < d.desc.Channels; c++ {
		if locked[c] {
			anyLocked = true
			continue
		}
		code := d.desc.MaxCode / 2
		if d.desc.Nonvolatile {
			var err error
			if code, err = d.GetNVWiper(c); err != nil {
				return err
			}
		}
		if err := d.SetWiper(code, c); err != nil {
			return err
		}
	}
	if !anyLocked {
		return d.write9(mcp4xxxAddrTCON, mcp4xxxTCONDefault)
	}
	return nil
}

func (d *MCP4XXXFull) checkAddress(address int, writable bool) error {
	ok := address == mcp4xxxAddrTCON || address < d.desc.Channels
	if d.desc.Nonvolatile {
		ok = ok || ((address == 2 || address == 3) && address-2 < d.desc.Channels) || (address >= mcp4xxxAddrData0 && address <= 0x0F)
		if address == mcp4xxxAddrStatus {
			ok = !writable
		}
	}
	if address < 0 || address > 0x0F || !ok {
		return fmt.Errorf("mcp4xxx: address 0x%02X not available on %s", address, d.desc.Name)
	}
	return nil
}

// ReadRegister does a raw 9-bit read of a memory address the part has.
func (d *MCP4XXXFull) ReadRegister(address int) (int, error) {
	if err := d.checkAddress(address, false); err != nil {
		return 0, err
	}
	return d.read9(address)
}

// WriteRegister does a raw write of a writable memory address the part has (0–0x3FF for
// wipers, 0–0x1FF otherwise).
func (d *MCP4XXXFull) WriteRegister(address, value int) error {
	if err := d.checkAddress(address, true); err != nil {
		return err
	}
	limit := 0x1FF
	if address < 2 {
		limit = 0x3FF
	}
	if value < 0 {
		value = 0
	}
	if value > limit {
		value = limit
	}
	return d.write9(address, value)
}

// SetRAB changes the resistance model used by the conversions (no I²C traffic); a negative
// rWiper leaves RW unchanged.
func (d *MCP4XXXFull) SetRAB(rAB, rWiper float64) {
	d.rAB = rAB
	if rWiper >= 0 {
		d.rWiper = rWiper
	}
}

// ReadStatus (NV) reads and decodes STATUS.
func (d *MCP4XXXFull) ReadStatus() (MCP4XXXStatus, error) {
	if err := d.requireNV(); err != nil {
		return MCP4XXXStatus{}, err
	}
	v, err := d.read9(mcp4xxxAddrStatus)
	if err != nil {
		return MCP4XXXStatus{}, err
	}
	st := MCP4XXXStatus{EEPROMBusy: v&mcp4xxxStatusEEWA != 0, WriteProtect: v&mcp4xxxStatusWP != 0}
	for c := 0; c < d.desc.Channels; c++ {
		st.WiperLock[c] = v&mcp4xxxStatusWL[c] != 0
	}
	return st, nil
}

// WaitReady (NV) polls STATUS.EEWA until the EEPROM write cycle has finished (tWC is 5 ms
// typical, 10 ms maximum); it returns ErrMCP4XXXTimeout after timeoutMs.
func (d *MCP4XXXFull) WaitReady(timeoutMs int) error {
	if err := d.requireNV(); err != nil {
		return err
	}
	for i := 0; i <= timeoutMs; i++ {
		v, err := d.read9(mcp4xxxAddrStatus)
		if err != nil {
			return err
		}
		if v&mcp4xxxStatusEEWA == 0 {
			return nil
		}
		time.Sleep(time.Millisecond)
	}
	return ErrMCP4XXXTimeout
}

// GetNVWiper (NV) reads the EEPROM wiper (power-up value), clamped to the max code.
func (d *MCP4XXXFull) GetNVWiper(channel int) (int, error) {
	if err := d.requireNV(); err != nil {
		return 0, err
	}
	if err := d.checkChannel(channel); err != nil {
		return 0, err
	}
	v, err := d.read9(mcp4xxxAddrNVWiper0 + channel)
	if v > d.desc.MaxCode {
		v = d.desc.MaxCode
	}
	return v, err
}

// SetNVWiper (NV) writes the EEPROM wiper (the volatile wiper is not changed) and waits for the write cycle.
func (d *MCP4XXXFull) SetNVWiper(code, channel int) error {
	if err := d.requireNV(); err != nil {
		return err
	}
	if err := d.checkChannel(channel); err != nil {
		return err
	}
	if err := d.write9(mcp4xxxAddrNVWiper0+channel, d.clamp(code)); err != nil {
		return err
	}
	return d.WaitReady(20)
}

// StoreWiper (NV) writes the current volatile wiper to the EEPROM wiper and returns the stored code.
func (d *MCP4XXXFull) StoreWiper(channel int) (int, error) {
	if err := d.requireNV(); err != nil {
		return 0, err
	}
	code, err := d.GetWiper(channel)
	if err != nil {
		return 0, err
	}
	return code, d.SetNVWiper(code, channel)
}

// ReadData (NV) reads general-purpose EEPROM register index (0–9).
func (d *MCP4XXXFull) ReadData(index int) (int, error) {
	if err := d.requireNV(); err != nil {
		return 0, err
	}
	if index < 0 || index > 9 {
		return 0, fmt.Errorf("mcp4xxx: data index %d out of range 0-9", index)
	}
	return d.read9(mcp4xxxAddrData0 + index)
}

// WriteData (NV) writes general-purpose EEPROM register index (0–9, value 0–0x1FF; uses one write cycle).
func (d *MCP4XXXFull) WriteData(index, value int) error {
	if err := d.requireNV(); err != nil {
		return err
	}
	if index < 0 || index > 9 {
		return fmt.Errorf("mcp4xxx: data index %d out of range 0-9", index)
	}
	if value < 0 {
		value = 0
	}
	if value > 0x1FF {
		value = 0x1FF
	}
	if err := d.write9(mcp4xxxAddrData0+index, value); err != nil {
		return err
	}
	return d.WaitReady(20)
}

// IsWiperLocked (NV) reports STATUS.WLx of a network.
func (d *MCP4XXXFull) IsWiperLocked(channel int) (bool, error) {
	if err := d.checkChannel(channel); err != nil {
		return false, err
	}
	st, err := d.ReadStatus()
	return st.WiperLock[channel], err
}

// IsWriteProtected (NV) reports STATUS.WP (hardware WP pin OR the nonvolatile bit).
func (d *MCP4XXXFull) IsWriteProtected() (bool, error) {
	st, err := d.ReadStatus()
	return st.WriteProtect, err
}

func (d *MCP4XXXFull) hvCommand(address int, decrement bool) error {
	if d.hvc == nil {
		return ErrMCP4XXXUnsupported
	}
	op := mcp4xxxOpInc
	if decrement {
		op = mcp4xxxOpDec
	}
	if err := d.hvc.Set(true); err != nil {
		return err
	}
	time.Sleep(time.Duration(d.hvcSettleMs) * time.Millisecond)
	err := d.connection.WriteReg(uint32(address<<4|op), nil)
	if relErr := d.hvc.Set(false); err == nil {
		err = relErr
	}
	if err != nil {
		return err
	}
	return d.WaitReady(20)
}

// SetWiperLock (NV, needs the hvc pin) enables (decrement command) or disables (increment) WiperLock of a
// network with a high-voltage command and waits for the EEPROM write cycle that stores the bit.
func (d *MCP4XXXFull) SetWiperLock(locked bool, channel int) error {
	if err := d.requireNV(); err != nil {
		return err
	}
	if err := d.checkChannel(channel); err != nil {
		return err
	}
	return d.hvCommand(mcp4xxxAddrNVWiper0+channel, locked)
}

// SetWriteProtect (NV, needs the hvc pin) sets or clears the nonvolatile EEPROM write-protect bit with a
// high-voltage command; the hardware WP pin (MCP4641 / MCP4661) can still block writes.
func (d *MCP4XXXFull) SetWriteProtect(enabled bool) error {
	if err := d.requireNV(); err != nil {
		return err
	}
	return d.hvCommand(mcp4xxxAddrWP, enabled)
}
