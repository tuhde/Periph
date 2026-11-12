package adcdac

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// DS1881 command bytes and configuration bits. The chip has no register map:
// a write is one or more command bytes (00pppppp wiper 0, 01pppppp wiper 1,
// 10xxxVZC configuration) and a read returns wiper 0, wiper 1 and the
// configuration register round-robin. The driver therefore uses the plain
// Connection Write / Read calls. Wipers and configuration live in EEPROM when
// V/NV = 0 (50 000 writes); the drivers default to volatile operation. The CE
// pin is active low and is left to the Connection's enable pin.
const (
	ds1881CmdWiper1   uint8 = 0x40
	ds1881CmdConfig   uint8 = 0x80
	ds1881CfgVolatile uint8 = 0x04
	ds1881CfgZC       uint8 = 0x02
	ds1881CfgOption2  uint8 = 0x01
	ds1881CfgMask     uint8 = 0x07
	// ds1881CfgDefault is volatile, zero-crossing on, Option 1.
	ds1881CfgDefault uint8 = ds1881CfgVolatile | ds1881CfgZC

	ds1881MuteDB float32 = 80.0
)

// DS1881I2CAddress is the 7-bit I²C address with A2 = A1 = A0 = 0 (0x28–0x2F
// with the address pins).
const DS1881I2CAddress uint8 = 0x28

// Errors returned by the DS1881 drivers.
var (
	// ErrDS1881Channel reports a channel other than 0 or 1.
	ErrDS1881Channel = errors.New("ds1881: channel must be 0 or 1")
	// ErrDS1881Option reports an attenuation option other than 1 or 2.
	ErrDS1881Option = errors.New("ds1881: option must be 1 or 2")
	// ErrDS1881Busy reports that the device kept NACKing (EEPROM write still running).
	ErrDS1881Busy = errors.New("ds1881: busy, EEPROM write did not finish")
)

// DS1881Config is the decoded configuration register.
type DS1881Config struct {
	Option       int  // 1 (63 positions + mute, 1 dB steps) or 2 (33 positions + mute)
	ZeroCrossing bool // zero-crossing detection enabled
	Nonvolatile  bool // wiper writes are stored in EEPROM
	Raw          uint8
}

func ds1881MutePosition(option int) int {
	if option == 1 {
		return 63
	}
	return 33
}

func ds1881PositionToDB(position, option int) float32 {
	if position >= ds1881MutePosition(option) {
		return ds1881MuteDB
	}
	if option == 1 || position <= 12 {
		return float32(position)
	}
	if position <= 24 {
		return float32(12 + 2*(position-12))
	}
	return float32(36 + 3*(position-24))
}

// ds1881Nearest returns the nearest non-mute position for db, clamped to the table.
func ds1881Nearest(db float32, option int) int {
	if db <= 0 {
		return 0
	}
	if option == 1 {
		p := int(db + 0.5)
		if p > 62 {
			p = 62
		}
		return p
	}
	best := 0
	bestErr := db
	for p := 1; p <= 32; p++ {
		e := ds1881PositionToDB(p, 2) - db
		if e < 0 {
			e = -e
		}
		if e < bestErr {
			best, bestErr = p, e
		}
	}
	return best
}

// DS1881Minimal sets and reads back the attenuation of the two channels of a
// DS1881 dual audio log-taper digital potentiometer (Maxim) in dB. Defaults:
// Option 1 (1 dB steps, 0 to −62 dB plus mute), volatile wipers (no EEPROM
// wear; both channels at mute after power-up), zero-crossing detection on.
type DS1881Minimal struct {
	conn   connection.Connection
	config uint8
	option int
	mute70 bool // Full: attenuations ≥ 70 dB map to mute
}

// NewDS1881Minimal creates a DS1881Minimal. It performs no I²C traffic; call
// Init once after power-up.
func NewDS1881Minimal(conn connection.Connection) (*DS1881Minimal, error) {
	return &DS1881Minimal{conn: conn, config: ds1881CfgDefault, option: 1}, nil
}

// Init runs the initialization sequence: it waits the 1 ms power-up time,
// reads the three bytes and rewrites the configuration (volatile,
// zero-crossing on, Option 1) only if it differs, waiting for the EEPROM
// write that this triggers. The wipers are not touched.
func (d *DS1881Minimal) Init() error {
	time.Sleep(time.Millisecond)
	raw, err := d.conn.Read(3)
	if err != nil {
		return err
	}
	if raw[2]&ds1881CfgMask != ds1881CfgDefault {
		if err := d.conn.Write([]byte{ds1881CmdConfig | ds1881CfgDefault}); err != nil {
			return err
		}
		if err := d.waitReady(0.05); err != nil {
			return err
		}
	}
	d.config = ds1881CfgDefault
	d.option = 1
	return nil
}

func (d *DS1881Minimal) waitReady(timeoutS float32) error {
	attempts := int(timeoutS*1000) + 1
	for i := 0; i < attempts; i++ {
		if _, err := d.conn.Read(1); err == nil {
			return nil
		}
		time.Sleep(time.Millisecond)
	}
	return ErrDS1881Busy
}

func (d *DS1881Minimal) writeCommands(data []byte) error {
	if err := d.conn.Write(data); err != nil {
		return err
	}
	if d.config&ds1881CfgVolatile == 0 {
		return d.waitReady(0.05)
	}
	return nil
}

func (d *DS1881Minimal) writePosition(channel, position int) error {
	if channel != 0 && channel != 1 {
		return ErrDS1881Channel
	}
	mute := ds1881MutePosition(d.option)
	if position < 0 {
		position = 0
	} else if position > mute {
		position = mute
	}
	return d.writeCommands([]byte{uint8(channel<<6 | position)})
}

func (d *DS1881Minimal) readAll() (uint8, uint8, uint8, error) {
	raw, err := d.conn.Read(3)
	if err != nil {
		return 0, 0, 0, err
	}
	return raw[0] & 0x3F, raw[1] & 0x3F, raw[2], nil
}

func (d *DS1881Minimal) dbToPos(db float32) int {
	if d.mute70 && db >= 70 {
		return ds1881MutePosition(d.option)
	}
	return ds1881Nearest(db, d.option)
}

// SetAttenuation sets a channel (0 or 1) to the nearest position: db is
// positive = quieter, rounded to the nearest step and clamped to 0…62 dB
// (Option 1).
func (d *DS1881Minimal) SetAttenuation(channel int, db float32) error {
	if channel != 0 && channel != 1 {
		return ErrDS1881Channel
	}
	return d.writePosition(channel, d.dbToPos(db))
}

// GetAttenuation returns a channel's attenuation in dB (80 for mute).
func (d *DS1881Minimal) GetAttenuation(channel int) (float32, error) {
	p, err := d.GetPosition(channel)
	if err != nil {
		return 0, err
	}
	return ds1881PositionToDB(int(p), d.option), nil
}

// Mute mutes a channel (position 63 in Option 1).
func (d *DS1881Minimal) Mute(channel int) error {
	return d.writePosition(channel, ds1881MutePosition(d.option))
}

// SetPosition writes a raw wiper position (0-63, 63 = mute; clamped).
func (d *DS1881Minimal) SetPosition(channel, position int) error {
	return d.writePosition(channel, position)
}

// GetPosition reads a wiper position (low 6 bits) with the three-byte
// round-robin read.
func (d *DS1881Minimal) GetPosition(channel int) (uint8, error) {
	if channel != 0 && channel != 1 {
		return 0, ErrDS1881Channel
	}
	p0, p1, _, err := d.readAll()
	if err != nil {
		return 0, err
	}
	if channel == 0 {
		return p0, nil
	}
	return p1, nil
}

// DS1881Full extends DS1881Minimal with stereo writes, Option 2,
// zero-crossing and EEPROM control, and table helpers. The driver caches the
// configuration (volatile, zero-crossing, option); Init and GetConfig refresh
// it from the chip.
type DS1881Full struct {
	DS1881Minimal
}

// NewDS1881Full creates a DS1881Full. It performs no I²C traffic; call Init
// once after power-up.
func NewDS1881Full(conn connection.Connection) (*DS1881Full, error) {
	m, _ := NewDS1881Minimal(conn)
	m.mute70 = true
	return &DS1881Full{DS1881Minimal: *m}, nil
}

// SetBoth writes both wipers in one transaction (no STOP between them); both
// positions are clamped to the current option.
func (d *DS1881Full) SetBoth(position0, position1 int) error {
	mute := ds1881MutePosition(d.option)
	data := make([]byte, 2)
	for ch, p := range [2]int{position0, position1} {
		if p < 0 {
			p = 0
		} else if p > mute {
			p = mute
		}
		data[ch] = uint8(ch<<6 | p)
	}
	return d.writeCommands(data)
}

// SetAttenuationBoth sets both channels' attenuation (stereo volume); db1
// defaults to db0 when omitted.
func (d *DS1881Full) SetAttenuationBoth(db0 float32, db1 ...float32) error {
	other := db0
	if len(db1) > 0 {
		other = db1[0]
	}
	return d.SetBoth(d.dbToPos(db0), d.dbToPos(other))
}

// Configure writes the configuration byte if it differs from the cached
// value. The configuration is stored in EEPROM, so a write waits for
// acknowledge polling. The wipers are not rewritten — positions keep their
// numeric value when the option changes. option is 1 or 2; nonvolatile
// stores wiper writes in EEPROM (50 000 writes).
func (d *DS1881Full) Configure(option int, zeroCrossing, nonvolatile bool) error {
	if option != 1 && option != 2 {
		return ErrDS1881Option
	}
	var config uint8
	if !nonvolatile {
		config |= ds1881CfgVolatile
	}
	if zeroCrossing {
		config |= ds1881CfgZC
	}
	if option == 2 {
		config |= ds1881CfgOption2
	}
	if config != d.config {
		if err := d.conn.Write([]byte{ds1881CmdConfig | config}); err != nil {
			return err
		}
		if err := d.waitReady(0.05); err != nil {
			return err
		}
	}
	d.config = config
	d.option = option
	return nil
}

// GetConfig reads the configuration register (fresh read; refreshes the cache).
func (d *DS1881Full) GetConfig() (DS1881Config, error) {
	_, _, raw, err := d.readAll()
	if err != nil {
		return DS1881Config{}, err
	}
	d.config = raw & ds1881CfgMask
	d.option = 1
	if raw&ds1881CfgOption2 != 0 {
		d.option = 2
	}
	return DS1881Config{
		Option:       d.option,
		ZeroCrossing: raw&ds1881CfgZC != 0,
		Nonvolatile:  raw&ds1881CfgVolatile == 0,
		Raw:          raw,
	}, nil
}

// SetOption switches between Option 1 and 2 (positions are not converted).
func (d *DS1881Full) SetOption(option int) error {
	return d.Configure(option, d.config&ds1881CfgZC != 0, d.config&ds1881CfgVolatile == 0)
}

// SetZeroCrossing enables or disables zero-crossing detection.
func (d *DS1881Full) SetZeroCrossing(enabled bool) error {
	return d.Configure(d.option, enabled, d.config&ds1881CfgVolatile == 0)
}

// SetNonvolatile selects where wiper writes go (V/NV bit): true stores
// subsequent wiper writes in EEPROM (rated for only 50 000 writes) and
// restores them at power-up.
func (d *DS1881Full) SetNonvolatile(enabled bool) error {
	return d.Configure(d.option, d.config&ds1881CfgZC != 0, enabled)
}

// Store persists the current wiper positions to EEPROM: it reads both
// positions, switches to nonvolatile mode if needed, rewrites both wiper
// bytes in one transaction, waits by acknowledge polling and restores
// volatile mode if it was active. The EEPROM is rated for only 50 000 writes.
func (d *DS1881Full) Store() error {
	p0, p1, _, err := d.readAll()
	if err != nil {
		return err
	}
	wasNV := d.config&ds1881CfgVolatile == 0
	zc := d.config&ds1881CfgZC != 0
	option := d.option
	if !wasNV {
		if err := d.Configure(option, zc, true); err != nil {
			return err
		}
	}
	if err := d.writeCommands([]byte{p0, ds1881CmdWiper1 | p1}); err != nil {
		return err
	}
	if !wasNV {
		return d.Configure(option, zc, false)
	}
	return nil
}

// WaitReady acknowledge-polls until the device answers (it NACKs while an
// EEPROM write is in progress); it returns ErrDS1881Busy after timeoutS
// seconds (default part: 0.05).
func (d *DS1881Full) WaitReady(timeoutS float32) error {
	return d.waitReady(timeoutS)
}

// ReadAll reads wiper 0, wiper 1 and the configuration byte in one
// transaction.
func (d *DS1881Full) ReadAll() (position0, position1, config uint8, err error) {
	return d.readAll()
}

// PositionToDB returns the attenuation of a position in the current option
// (80 for mute and above).
func (d *DS1881Full) PositionToDB(position int) float32 {
	return ds1881PositionToDB(position, d.option)
}

// DBToPosition returns the nearest position for an attenuation in the current
// option; 70 dB and above map to mute, negative to 0.
func (d *DS1881Full) DBToPosition(db float32) uint8 {
	return uint8(d.dbToPos(db))
}

// MaxPosition returns the last non-mute position of the current option: 62
// (Option 1) or 32 (Option 2).
func (d *DS1881Full) MaxPosition() uint8 {
	return uint8(ds1881MutePosition(d.option) - 1)
}

// Reset selects volatile, zero-crossing on, Option 1 and mutes both wipers,
// sent as one transaction (configuration byte, then both wiper bytes); it
// waits for the configuration EEPROM write.
func (d *DS1881Full) Reset() error {
	if err := d.conn.Write([]byte{ds1881CmdConfig | ds1881CfgDefault, 63, ds1881CmdWiper1 | 63}); err != nil {
		return err
	}
	d.config = ds1881CfgDefault
	d.option = 1
	return d.waitReady(0.05)
}
