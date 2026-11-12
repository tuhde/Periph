package display

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// SSD1306 geometry.
const (
	// SSD1306Width and SSD1306Height are the maximum dimensions of the SSD1306 RAM (128 columns x 64 rows).
	SSD1306Width  = 128
	SSD1306Height = 64
)

// ErrSSD1306Argument is returned for a size, position or buffer argument that is out of range.
var ErrSSD1306Argument = errors.New("ssd1306: argument out of range")

// ErrSSD1306ScrollActive is returned by Show and ShowPage while a hardware scroll is active: RAM access is
// prohibited then; call ScrollStop first.
var ErrSSD1306ScrollActive = errors.New("ssd1306: RAM access while a scroll is active")

const (
	ssd1306CmdAddrMode   = 0x20
	ssd1306CmdColRange   = 0x21
	ssd1306CmdPageRange  = 0x22
	ssd1306CmdStartLine  = 0x40
	ssd1306CmdContrast   = 0x81
	ssd1306CmdChargePump = 0x8D
	ssd1306CmdSegRemap   = 0xA0
	ssd1306CmdScrollArea = 0xA3
	ssd1306CmdAllOn      = 0xA4
	ssd1306CmdInvert     = 0xA6
	ssd1306CmdMultiplex  = 0xA8
	ssd1306CmdDisplay    = 0xAE
	ssd1306CmdPage       = 0xB0
	ssd1306CmdScan       = 0xC0
	ssd1306CmdOffset     = 0xD3
	ssd1306CmdClock      = 0xD5
	ssd1306CmdPrecharge  = 0xD9
	ssd1306CmdCom        = 0xDA
	ssd1306CmdVcomh      = 0xDB
)

// SSD1306AddressingMode selects how Show writes the display RAM (see SetAddressingMode).
type SSD1306AddressingMode byte

// RAM addressing modes.
const (
	// SSD1306AddrHorizontal advances the column pointer first, then the page (0x20 0x00).
	SSD1306AddrHorizontal SSD1306AddressingMode = 0
	// SSD1306AddrVertical advances the page pointer first, then the column (0x20 0x01).
	SSD1306AddrVertical SSD1306AddressingMode = 1
	// SSD1306AddrPage writes one page at a time with page + column commands (0x20 0x02, reset default).
	SSD1306AddrPage SSD1306AddressingMode = 2
)

// SSD1306Minimal is the SSD1306 128 x 64 OLED controller-driver — minimal interface.
//
// Drives a monochrome SSD1306 OLED panel (0.96" 128 x 64 or 128 x 32 modules) over I2C at 0x3C (SA0 = low) or
// 0x3D (SA0 = high). The chip has an exact 128-column RAM, so there is no column offset.
//
// All drawing goes into a framebuffer of width * height / 8 bytes (page-major, bit 0 = top pixel of the page);
// Show sends it page by page (page addressing, the reset default). The initialisation follows the datasheet and
// the charge-pump application note: setup stream with the display off (charge pump, page addressing), RAM
// cleared, display on + 100 ms. With externalVCC false the internal charge pump makes the OLED supply VCC.
type SSD1306Minimal struct {
	bus         monoOLEDBus
	canvas      *monoFramebuffer
	externalVCC bool
	mode        SSD1306AddressingMode
	scrolling   bool
	adc         byte
	scan        byte
}

// NewSSD1306Minimal creates the driver on an I2C connection and runs the initialisation sequence (about 100 ms).
//
// conn is the connection at 0x3C or 0x3D. width is 1..128 and height a multiple of 8, 16..64 (pass 0 for the
// defaults 128 and 64; heights up to 32 use sequential COM wiring). externalVCC keeps the charge pump off when the
// board supplies VCC. It returns ErrSSD1306Argument for bad arguments.
func NewSSD1306Minimal(conn connection.Connection, width, height int, externalVCC bool) (*SSD1306Minimal, error) {
	return newSSD1306(&monoOLEDI2C{conn}, width, height, externalVCC)
}

// NewSSD1306SpiMinimal creates the driver on a 4-wire SPI display connection (CS, D/C, RES optional, no BUSY; SPI
// mode 0, up to 10 MHz, 4 MHz recommended; chip strapped BS0 = BS1 = BS2 = 0). Every command byte, including
// arguments, is sent with D/C low; RAM data with D/C high. Arguments as for NewSSD1306Minimal.
func NewSSD1306SpiMinimal(conn connection.DisplayConn, width, height int, externalVCC bool) (*SSD1306Minimal, error) {
	return newSSD1306(&monoOLEDSPI{conn}, width, height, externalVCC)
}

func newSSD1306(bus monoOLEDBus, width, height int, externalVCC bool) (*SSD1306Minimal, error) {
	if width == 0 {
		width = SSD1306Width
	}
	if height == 0 {
		height = SSD1306Height
	}
	if width < 1 || width > SSD1306Width || height < 16 || height > SSD1306Height || height%8 != 0 {
		return nil, ErrSSD1306Argument
	}
	d := &SSD1306Minimal{bus: bus, canvas: newMonoFramebuffer(width, height), externalVCC: externalVCC, mode: SSD1306AddrPage}
	if err := d.initSequence(); err != nil {
		return nil, err
	}
	return d, nil
}

// Width returns the visible columns of the framebuffer.
func (d *SSD1306Minimal) Width() int { return d.canvas.width }

// Height returns the rows of the framebuffer.
func (d *SSD1306Minimal) Height() int { return d.canvas.height }

// Clear zeroes the framebuffer. Call Show to update the panel.
func (d *SSD1306Minimal) Clear() { d.canvas.fill(false) }

// SetPixel sets or clears one pixel in the framebuffer. Out-of-range coordinates are ignored.
func (d *SSD1306Minimal) SetPixel(x, y int, on bool) { d.canvas.setPixel(x, y, on) }

// Show writes the whole framebuffer to the display RAM. Page addressing (default): per page a page + column
// command stream, then width data bytes. Horizontal / vertical addressing: column and page range commands, then
// one data stream. It returns ErrSSD1306ScrollActive while a hardware scroll is active.
func (d *SSD1306Minimal) Show() error { return d.showAll() }

// ---- internals ------------------------------------------------------------------------------

func (d *SSD1306Minimal) command(b ...byte) error { return d.bus.writeCommands(b) }

func ssd1306Address(page int) []byte {
	return []byte{byte(ssd1306CmdPage | page&7), 0x00, 0x10}
}

func (d *SSD1306Minimal) showPage(page int) error {
	if err := d.bus.writeCommands(ssd1306Address(page)); err != nil {
		return err
	}
	return d.bus.writeData(d.canvas.page(page))
}

func (d *SSD1306Minimal) setRanges(firstPage, lastPage int) error {
	return d.command(ssd1306CmdColRange, 0, byte(d.canvas.width-1), ssd1306CmdPageRange, byte(firstPage), byte(lastPage))
}

func (d *SSD1306Minimal) showAll() error {
	if d.scrolling {
		return ErrSSD1306ScrollActive
	}
	if d.mode == SSD1306AddrPage {
		for page := 0; page < d.canvas.pages; page++ {
			if err := d.showPage(page); err != nil {
				return err
			}
		}
		return nil
	}
	if err := d.setRanges(0, d.canvas.pages-1); err != nil {
		return err
	}
	if d.mode == SSD1306AddrHorizontal {
		return d.bus.writeData(d.canvas.data)
	}
	return d.bus.writeData(d.canvas.transposed())
}

// initSequence is the datasheet power-on flow: reset, setup stream, RAM clear, display on.
func (d *SSD1306Minimal) initSequence() error {
	if err := d.bus.hardwareReset(); err != nil {
		return err
	}
	time.Sleep(time.Millisecond)
	com, pump := byte(0x12), byte(0x14)
	if d.canvas.height <= 32 {
		com = 0x02
	}
	if d.externalVCC {
		pump = 0x10
	}
	if err := d.command(ssd1306CmdDisplay,
		ssd1306CmdClock, 0x80,
		ssd1306CmdMultiplex, byte(d.canvas.height-1),
		ssd1306CmdOffset, 0x00,
		ssd1306CmdStartLine,
		ssd1306CmdChargePump, pump,
		ssd1306CmdAddrMode, 0x02,
		ssd1306CmdSegRemap|d.adc,
		ssd1306CmdScan|d.scan<<3,
		ssd1306CmdCom, com,
		ssd1306CmdContrast, 0x7F,
		ssd1306CmdPrecharge, 0x22,
		ssd1306CmdVcomh, 0x20,
		ssd1306CmdAllOn,
		ssd1306CmdInvert); err != nil {
		return err
	}
	blank := make([]byte, SSD1306Width)
	for page := 0; page < 8; page++ {
		if err := d.bus.writeCommands(ssd1306Address(page)); err != nil {
			return err
		}
		if err := d.bus.writeData(blank); err != nil {
			return err
		}
	}
	if err := d.command(ssd1306CmdDisplay | 1); err != nil {
		return err
	}
	time.Sleep(100 * time.Millisecond)
	return nil
}

// SSD1306Full is the SSD1306 OLED controller-driver — full interface.
//
// It extends SSD1306Minimal with drawing primitives, contrast, sleep, invert, all-on test, flip, start line,
// display offset, multiplex ratio, clock / oscillator, pre-charge, COM configuration, VCOMH level, charge pump,
// addressing mode, hardware scrolling and raw commands. The SSD1306 cannot be read over its serial interfaces.
type SSD1306Full struct {
	*SSD1306Minimal
}

// NewSSD1306Full creates the driver on an I2C connection and runs the initialisation sequence (arguments as for
// NewSSD1306Minimal).
func NewSSD1306Full(conn connection.Connection, width, height int, externalVCC bool) (*SSD1306Full, error) {
	m, err := NewSSD1306Minimal(conn, width, height, externalVCC)
	if err != nil {
		return nil, err
	}
	return &SSD1306Full{m}, nil
}

// NewSSD1306SpiFull creates the full driver on a 4-wire SPI display connection (see NewSSD1306SpiMinimal).
func NewSSD1306SpiFull(conn connection.DisplayConn, width, height int, externalVCC bool) (*SSD1306Full, error) {
	m, err := NewSSD1306SpiMinimal(conn, width, height, externalVCC)
	if err != nil {
		return nil, err
	}
	return &SSD1306Full{m}, nil
}

// GetPixel returns a framebuffer pixel; out-of-range coordinates read as false.
func (d *SSD1306Full) GetPixel(x, y int) bool { return d.canvas.getPixel(x, y) }

// Fill sets (or clears) the whole framebuffer.
func (d *SSD1306Full) Fill(on bool) { d.canvas.fill(on) }

// DrawLine draws a Bresenham line into the framebuffer.
func (d *SSD1306Full) DrawLine(x0, y0, x1, y1 int, on bool) { d.canvas.drawLine(x0, y0, x1, y1, on) }

// DrawRect draws a rectangle outline or a filled rectangle into the framebuffer.
func (d *SSD1306Full) DrawRect(x, y, w, h int, on, fill bool) {
	d.canvas.drawRect(x, y, w, h, on, fill)
}

// Blit copies a column-major bitmap (8 pixels per byte, bit 0 = top) into the framebuffer. columns holds
// heightPages groups of columns, page by page: byte p*n+c is column c of page row p (n = len(columns) /
// heightPages). The bitmap is clipped to the framebuffer; y must be a multiple of 8, else ErrSSD1306Argument.
func (d *SSD1306Full) Blit(x, y int, columns []byte, heightPages int) error {
	if err := d.canvas.blit(x, y, columns, heightPages); err != nil {
		return ErrSSD1306Argument
	}
	return nil
}

// ShowPage writes one framebuffer page to the display RAM (cheaper partial update); out-of-range pages are
// ignored. It returns ErrSSD1306ScrollActive while a hardware scroll is active.
func (d *SSD1306Full) ShowPage(page int) error {
	if d.scrolling {
		return ErrSSD1306ScrollActive
	}
	if page < 0 || page >= d.canvas.pages {
		return nil
	}
	if d.mode == SSD1306AddrPage {
		return d.showPage(page)
	}
	if err := d.setRanges(page, page); err != nil {
		return err
	}
	return d.bus.writeData(d.canvas.page(page))
}

// SetContrast sets the segment output current (contrast), 0..255 (reset 0x7F).
func (d *SSD1306Full) SetContrast(value uint8) error { return d.command(ssd1306CmdContrast, value) }

func ssd1306Bit(b bool) byte {
	if b {
		return 1
	}
	return 0
}

// SetDisplay switches the display on or off (RAM and settings are kept).
func (d *SSD1306Full) SetDisplay(on bool) error { return d.command(ssd1306CmdDisplay | ssd1306Bit(on)) }

// Sleep turns the display off (0xAE); RAM and settings are kept and stay writable. Switch VCC off afterwards.
func (d *SSD1306Full) Sleep() error { return d.SetDisplay(false) }

// Wake turns the display on and waits 100 ms (tAF) for SEG / COM to turn on.
func (d *SSD1306Full) Wake() error {
	if err := d.SetDisplay(true); err != nil {
		return err
	}
	time.Sleep(100 * time.Millisecond)
	return nil
}

// SetInvert inverts the displayed image without changing RAM.
func (d *SSD1306Full) SetInvert(on bool) error { return d.command(ssd1306CmdInvert | ssd1306Bit(on)) }

// SetAllOn forces all pixels on (test mode, RAM unchanged).
func (d *SSD1306Full) SetAllOn(on bool) error { return d.command(ssd1306CmdAllOn | ssd1306Bit(on)) }

// SetFlip mirrors the columns (segment re-map, ADC) and/or rows (COM scan direction); the framebuffer is unchanged.
func (d *SSD1306Full) SetFlip(horizontal, vertical bool) error {
	d.adc = ssd1306Bit(horizontal)
	d.scan = ssd1306Bit(vertical)
	return d.command(ssd1306CmdSegRemap|d.adc, ssd1306CmdScan|d.scan<<3)
}

// SetStartLine sets the RAM line shown at COM0 (hardware vertical scroll), 0..63.
func (d *SSD1306Full) SetStartLine(line uint8) error {
	return d.command(ssd1306CmdStartLine | line&0x3F)
}

// SetDisplayOffset shifts the COM mapping, 0..63.
func (d *SSD1306Full) SetDisplayOffset(offset uint8) error {
	return d.command(ssd1306CmdOffset, offset&0x3F)
}

// SetMultiplex sets the multiplex ratio, 16..64.
func (d *SSD1306Full) SetMultiplex(ratio uint8) error {
	return d.command(ssd1306CmdMultiplex, (ratio-1)&0x3F)
}

// SetClock sets the clock divide ratio (1..16) and the oscillator setting (0..15, 8 = reset).
func (d *SSD1306Full) SetClock(divide, osc uint8) error {
	return d.command(ssd1306CmdClock, (osc&0x0F)<<4|(divide-1)&0x0F)
}

// SetPrecharge sets the pre-charge phase 1 and phase 2 periods (1..15 display clocks each).
func (d *SSD1306Full) SetPrecharge(phase1, phase2 uint8) error {
	return d.command(ssd1306CmdPrecharge, (phase2&0x0F)<<4|phase1&0x0F)
}

// SetComConfig selects the COM pin configuration: alternative (true, reset) or sequential; remap enables the
// COM left / right remap.
func (d *SSD1306Full) SetComConfig(alternative, remap bool) error {
	v := byte(0x02)
	if alternative {
		v |= 0x10
	}
	if remap {
		v |= 0x20
	}
	return d.command(ssd1306CmdCom, v)
}

// SetVcomh sets the VCOMH deselect level (only bits 6:4 used; reset 0x20).
func (d *SSD1306Full) SetVcomh(value uint8) error { return d.command(ssd1306CmdVcomh, value&0x70) }

// SetChargePump enables (0x14) or disables (0x10) the internal charge pump; takes effect with the display on.
func (d *SSD1306Full) SetChargePump(enabled bool) error {
	if enabled {
		return d.command(ssd1306CmdChargePump, 0x14)
	}
	return d.command(ssd1306CmdChargePump, 0x10)
}

// SetAddressingMode selects the RAM addressing mode used by Show (reset: SSD1306AddrPage).
func (d *SSD1306Full) SetAddressingMode(mode SSD1306AddressingMode) error {
	if mode > SSD1306AddrPage {
		return ErrSSD1306Argument
	}
	d.mode = mode
	return d.command(ssd1306CmdAddrMode, byte(mode))
}

// SetScrollHorizontal sets up a horizontal scroll, one column per step (start with ScrollStart). interval is a
// table index 0..7 = 5, 64, 128, 256, 3, 4, 25, 2 frames per step. Do not call while scrolling is active.
func (d *SSD1306Full) SetScrollHorizontal(right bool, startPage, endPage, interval uint8) error {
	cmd := byte(0x27)
	if right {
		cmd = 0x26
	}
	return d.command(cmd, 0x00, startPage&7, interval&7, endPage&7, 0x00, 0xFF)
}

// SetScrollVertical sets up a vertical + horizontal (diagonal) scroll (rows 1..63 per step).
func (d *SSD1306Full) SetScrollVertical(right bool, startPage, endPage, interval, rows uint8) error {
	cmd := byte(0x2A)
	if right {
		cmd = 0x29
	}
	return d.command(cmd, 0x00, startPage&7, interval&7, endPage&7, rows&0x3F)
}

// SetScrollArea sets the vertical scroll area: topFixed rows (0..63) fixed, scrollRows (1..64) scrolling.
func (d *SSD1306Full) SetScrollArea(topFixed, scrollRows uint8) error {
	return d.command(ssd1306CmdScrollArea, topFixed&0x3F, scrollRows&0x7F)
}

// ScrollStart activates the configured scroll (0x2F); Show and ShowPage are refused until ScrollStop.
func (d *SSD1306Full) ScrollStart() error {
	if err := d.command(0x2F); err != nil {
		return err
	}
	d.scrolling = true
	return nil
}

// ScrollStop deactivates scrolling (0x2E) and rewrites the RAM from the framebuffer, as the datasheet requires.
func (d *SSD1306Full) ScrollStop() error {
	if err := d.command(0x2E); err != nil {
		return err
	}
	d.scrolling = false
	return d.showAll()
}

// SendCommand sends raw bytes as one command stream.
func (d *SSD1306Full) SendCommand(b ...byte) error { return d.command(b...) }
