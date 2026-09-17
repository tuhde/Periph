"""MPR121 proximity capacitive touch sensor controller.

Provides 12-electrode touch/release detection (with an optional 13th
ELEPROX virtual electrode) using the chip's constant-DC-current charge
scheme. The INT pin asserts low on any touch or release event; the
driver exposes it via on_interrupt() / poll_interrupt() in the Full
class. I²C address is selected by the ADDR pin (0x5A/0x5B/0x5C/0x5D).

Default configuration baked into the Minimal stage:

    Touch threshold  = 12 for each of ELE0-ELE11
    Release threshold = 6 for each of ELE0-ELE11
    MHDR = NHDR = MHDF = NHDF = 1 (baseline filter defaults)
    CDC_CONFIG = 0x10 (16 µA global CDC; FFI = 6 samples)
    CDT_CONFIG = 0x24 (CDT = 1 µS, ESI = 16 ms sample interval)
    AUTOCONFIG0 = 0x0B (FFI=00, BVA=10, ARE=1, ACE=1)
    USL = 0xC9, TL = 0xB4, LSL = 0x82 (3.3 V VDD)
    ECR = 0x8C (CL=10, ELEPROX_EN=00, ELE_EN=12 -> all 12)
"""


class Mpr121Minimal:
    """MPR121 capacitive touch controller — minimal interface.

    Provides 12-bit touch status with no configuration beyond the
    connection. Performs soft reset, applies default touch/release
    thresholds, enables the chip's automatic CDC/CDT configuration,
    and enters Run Mode on all 12 electrodes at construction.

    Args:
        connection: Configured I2C connection pointing at the device.
    """

    _REG_ELE0_7_TOUCH  = 0x00
    _REG_ELE8_PROX_TCH = 0x01
    _REG_ELE0_7_OOR    = 0x02
    _REG_ELE8_PROX_OOR = 0x03
    _REG_MHDR          = 0x2B
    _REG_NHDR          = 0x2C
    _REG_MHDF          = 0x2F
    _REG_NHDF          = 0x30
    _REG_E0TTH         = 0x41
    _REG_E0RTH         = 0x42
    _REG_CDC_CONFIG    = 0x5C
    _REG_CDT_CONFIG    = 0x5D
    _REG_ECR           = 0x5E
    _REG_AUTOCONFIG0   = 0x7B
    _REG_AUTOCONFIG1   = 0x7C
    _REG_USL           = 0x7D
    _REG_LSL           = 0x7E
    _REG_TL            = 0x7F
    _REG_SRST          = 0x80

    _SOFT_RESET_KEY = 0x63

    _TOUCH_DEFAULT  = 12
    _RELEASE_DEFAULT = 6
    _FFI_DEFAULT = 0x10
    _ESI_DEFAULT = 0x24
    _AUTOCONFIG0_DEFAULT = 0x0B
    _ECR_DEFAULT = 0x8C
    _USL_3V3 = 0xC9
    _TL_3V3  = 0xB4
    _LSL_3V3  = 0x82

    def __init__(self, connection):
        self._connection = connection
        self._reset()
        self._write_reg(self._REG_MHDR, 0x01)
        self._write_reg(self._REG_NHDR, 0x01)
        self._write_reg(self._REG_MHDF, 0x01)
        self._write_reg(self._REG_NHDF, 0x01)
        self._write_reg(self._REG_CDC_CONFIG, self._FFI_DEFAULT)
        self._write_reg(self._REG_CDT_CONFIG, self._ESI_DEFAULT)
        self._write_reg(self._REG_USL, self._USL_3V3)
        self._write_reg(self._REG_TL,  self._TL_3V3)
        self._write_reg(self._REG_LSL, self._LSL_3V3)
        self._write_reg(self._REG_AUTOCONFIG0, self._AUTOCONFIG0_DEFAULT)
        for n in range(12):
            self._write_reg(self._REG_E0TTH + 2 * n, self._TOUCH_DEFAULT)
            self._write_reg(self._REG_E0RTH + 2 * n, self._RELEASE_DEFAULT)
        self._write_reg(self._REG_ECR, self._ECR_DEFAULT)

    def _reset(self):
        self._write_reg(self._REG_SRST, self._SOFT_RESET_KEY)
        import time
        time.sleep(0.001)

    def _write_reg(self, reg, value):
        self._connection.write(bytes([reg & 0xFF, value & 0xFF]))

    def _read_reg(self, reg):
        return self._connection.write_read(bytes([reg & 0xFF]), 1)[0]

    def touched(self):
        """Read the 12-bit electrode touch bitmask.

        Reads ELE0_7_TOUCH and ELE8_PROX_TOUCH as a coherent two-byte
        snapshot from register 0x00; ELEPROX is at bit 4 of the second
        byte and is masked out (returns 0–4095 for ELE0–ELE11 only).

        Returns:
            int: 12-bit bitmask; bit n = 1 if ELEn is currently touched.
        """
        raw = self._connection.write_read(bytes([self._REG_ELE0_7_TOUCH]), 2)
        return raw[0] | ((raw[1] & 0x0F) << 8)

    def is_touched(self, electrode):
        """Check whether a single electrode is currently touched.

        Args:
            electrode: Electrode index 0–11.

        Returns:
            bool: True if ELE_electrode is currently touched.
        """
        if not 0 <= electrode <= 11:
            raise ValueError('electrode must be in 0..11')
        return bool(self.touched() & (1 << electrode))


class Mpr121Full(Mpr121Minimal):
    """MPR121 full interface — extends Mpr121Minimal with configuration and interrupt support.

    Adds explicit Stop/Run control, per-electrode and per-proximity
    threshold configuration, filtered-data and baseline access, baseline
    filter and AFE (sampling) configuration, debounce, autoconfig
    recomputation, OOR status, over-current flag clear, and interrupt
    delivery via on_interrupt() / off_interrupt() / poll_interrupt().

    The chip's INT pin is active-low open-drain and asserts on any
    touch or release on ELE0–ELE11 (or ELEPROX when enabled). Reading
    ELE0_7_TOUCH and ELE8_PROX_TOUCH as a multi-byte read clears the
    interrupt.

    Args:
        connection: Configured I2C connection pointing at the device.
    """

    SOURCE_OOR = 0x04
    SOURCE_ARF = 0x02
    SOURCE_ACF = 0x01

    def __init__(self, connection):
        super().__init__(connection)
        self._callback = None
        self._poll_thread = None
        self._poll_stop = False

    def reset(self):
        """Software-reset the chip and re-apply Minimal defaults."""
        self._reset()
        self._write_reg(self._REG_MHDR, 0x01)
        self._write_reg(self._REG_NHDR, 0x01)
        self._write_reg(self._REG_MHDF, 0x01)
        self._write_reg(self._REG_NHDF, 0x01)
        self._write_reg(self._REG_CDC_CONFIG, self._FFI_DEFAULT)
        self._write_reg(self._REG_CDT_CONFIG, self._ESI_DEFAULT)
        self._write_reg(self._REG_USL, self._USL_3V3)
        self._write_reg(self._REG_TL,  self._TL_3V3)
        self._write_reg(self._REG_LSL, self._LSL_3V3)
        self._write_reg(self._REG_AUTOCONFIG0, self._AUTOCONFIG0_DEFAULT)
        for n in range(12):
            self._write_reg(self._REG_E0TTH + 2 * n, self._TOUCH_DEFAULT)
            self._write_reg(self._REG_E0RTH + 2 * n, self._RELEASE_DEFAULT)
        self._write_reg(self._REG_ECR, self._ECR_DEFAULT)

    def stop(self):
        """Enter Stop Mode (ECR=0x00). Required before writing most config registers."""
        self._write_reg(self._REG_ECR, 0x00)

    def start(self, n_electrodes=12, cl=2, eleprox_en=0):
        """Enter Run Mode with the given electrode configuration.

        Args:
            n_electrodes: Number of electrodes to enable 1-12.
            cl: Calibration lock / baseline init 0-3
                (0=tracking, init from baseline reg; 1=tracking off;
                 2=tracking, init from 5 MSBs of first measurement;
                 3=tracking, init from all 10 bits of first measurement).
            eleprox_en: Proximity enable 0-3
                (0=off, 1=ELE0-ELE1, 2=ELE0-ELE3, 3=ELE0-ELE11 summed).
        """
        if not 1 <= n_electrodes <= 12:
            raise ValueError('n_electrodes must be in 1..12')
        if not 0 <= cl <= 3:
            raise ValueError('cl must be in 0..3')
        if not 0 <= eleprox_en <= 3:
            raise ValueError('eleprox_en must be in 0..3')
        ecr = ((cl & 0x03) << 6) | ((eleprox_en & 0x03) << 4) | (n_electrodes & 0x0F)
        self._write_reg(self._REG_ECR, ecr)

    def configure_thresholds(self, electrode, touch, release):
        """Set touch and release thresholds for a single electrode.

        Args:
            electrode: Electrode index 0-11.
            touch: Touch threshold 0-255.
            release: Release threshold 0-255 (must be < touch).
        """
        if not 0 <= electrode <= 11:
            raise ValueError('electrode must be in 0..11')
        self._write_reg(self._REG_E0TTH + 2 * electrode, touch & 0xFF)
        self._write_reg(self._REG_E0RTH + 2 * electrode, release & 0xFF)

    def configure_all_thresholds(self, touch, release):
        """Apply the same touch and release thresholds to all 12 electrodes."""
        for n in range(12):
            self.configure_thresholds(n, touch, release)

    def configure_proximity_thresholds(self, touch, release):
        """Set ELEPROX touch and release thresholds.

        Args:
            touch: ELEPROX touch threshold 0-255.
            release: ELEPROX release threshold 0-255.
        """
        self._write_reg(0x59, touch & 0xFF)
        self._write_reg(0x5A, release & 0xFF)

    def filtered(self, electrode):
        """Read the 10-bit filtered capacitance data for an electrode.

        Args:
            electrode: 0-11 for ELE0-ELE11, 12 for ELEPROX.

        Returns:
            int: 10-bit value; inversely proportional to capacitance.
        """
        if not 0 <= electrode <= 12:
            raise ValueError('electrode must be in 0..12')
        if electrode == 12:
            addr = 0x1C
        else:
            addr = 0x04 + 2 * electrode
        raw = self._connection.write_read(bytes([addr]), 2)
        return (raw[0] | ((raw[1] & 0x03) << 8))

    def baseline(self, electrode):
        """Read the 10-bit baseline for an electrode.

        The chip stores only the 8 MSBs of the baseline; the returned
        value is shifted left by 2 to align with `filtered()`.

        Args:
            electrode: 0-11 for ELE0-ELE11, 12 for ELEPROX.

        Returns:
            int: 10-bit baseline value.
        """
        if not 0 <= electrode <= 12:
            raise ValueError('electrode must be in 0..12')
        if electrode == 12:
            addr = 0x2A
        else:
            addr = 0x1E + electrode
        return self._read_reg(addr) << 2

    def set_baseline(self, electrode, value):
        """Write a baseline value (Stop Mode only).

        Args:
            electrode: 0-11 for ELE0-ELE11, 12 for ELEPROX.
            value: 10-bit value 0-1023 (only the 8 MSBs are stored).
        """
        if not 0 <= electrode <= 12:
            raise ValueError('electrode must be in 0..12')
        if electrode == 12:
            addr = 0x2A
        else:
            addr = 0x1E + electrode
        self._write_reg(addr, (value >> 2) & 0xFF)

    def oor_status(self):
        """Read the 13-bit out-of-range bitmask.

        Returns:
            int: bits 0-11 = ELE0-ELE11 OOR, bit 12 = ELEPROX OOR.
        """
        raw = self._connection.write_read(bytes([self._REG_ELE0_7_OOR]), 2)
        return raw[0] | ((raw[1] & 0x1F) << 8)

    def configure_baseline_filter(self, mhdr, nhdr, nclr, fdlr,
                                   mhdf, nhdf, nclf, fdlf,
                                   nhdt, nclt, fdlt):
        """Set the global baseline filter parameters (Stop Mode).

        Args:
            mhdr: Max Half Delta Rising 1-63.
            nhdr: Noise Half Delta Rising 1-63.
            nclr: Noise Count Limit Rising 0-255.
            fdlr: Filter Delay Count Rising 0-255.
            mhdf: Max Half Delta Falling 1-63.
            nhdf: Noise Half Delta Falling 1-63.
            nclf: Noise Count Limit Falling 0-255.
            fdlf: Filter Delay Count Falling 0-255.
            nhdt: NHD Amount Touched 1-63.
            nclt: Noise Count Limit Touched 0-255.
            fdlt: Filter Delay Count Touched 0-255.
        """
        self._write_reg(self._REG_MHDR, mhdr & 0x3F)
        self._write_reg(self._REG_NHDR, nhdr & 0x3F)
        self._write_reg(0x2D, nclr & 0xFF)
        self._write_reg(0x2E, fdlr & 0xFF)
        self._write_reg(self._REG_MHDF, mhdf & 0x3F)
        self._write_reg(self._REG_NHDF, nhdf & 0x3F)
        self._write_reg(0x31, nclf & 0xFF)
        self._write_reg(0x32, fdlf & 0xFF)
        self._write_reg(0x33, nhdt & 0x3F)
        self._write_reg(0x34, nclt & 0xFF)
        self._write_reg(0x35, fdlt & 0xFF)

    def configure_sampling(self, cdc, cdt, ffi, sfi, esi):
        """Set global AFE (sampling) configuration (Stop Mode).

        Args:
            cdc: Global CDC 0-63 µA.
            cdt: Global CDT 0-7 (0=disabled, 1=0.5 µs, ..., 7=32 µs).
            ffi: First Filter Iterations 0-3 (6/10/18/34 samples).
            sfi: Second Filter Iterations 0-3 (4/6/10/18 samples).
            esi: Electrode Sample Interval 0-7 (1/2/4/8/16/32/64/128 ms).
        """
        cdc_cfg = ((ffi & 0x03) << 6) | (cdc & 0x3F)
        cdt_cfg = ((cdt & 0x07) << 5) | ((sfi & 0x03) << 2) | (esi & 0x07)
        self._write_reg(self._REG_CDC_CONFIG, cdc_cfg)
        self._write_reg(self._REG_CDT_CONFIG, cdt_cfg)

    def configure_debounce(self, touch, release):
        """Set debounce counts (Stop Mode).

        Args:
            touch: Debounce count for touch 0-7.
            release: Debounce count for release 0-7.
        """
        deb = ((release & 0x07) << 4) | (touch & 0x07)
        self._write_reg(0x5B, deb)

    def configure_autoconfig(self, vdd_mv=3300, retry=0, scts=False, are=True, ace=True):
        """Compute USL/TL/LSL from VDD and write autoconfig registers (Stop Mode).

        Args:
            vdd_mv: VDD supply in millivolts (e.g. 3300).
            retry: Auto-retry on autoconfig failure 0-3 (0=none, 1=2x, 2=4x, 3=8x).
            scts: Skip Charge Time Search — use current CDT/CDTx.
            are: Auto-Reconfiguration Enable (retry on OOR each scan).
            ace: Auto-Configuration Enable (run once on Stop->Run transition).
        """
        usl = int((vdd_mv - 700) / vdd_mv * 256)
        tl  = int(usl * 0.9)
        lsl = int(usl * 0.65)
        self._write_reg(self._REG_USL, usl & 0xFF)
        self._write_reg(self._REG_TL,  tl & 0xFF)
        self._write_reg(self._REG_LSL, lsl & 0xFF)
        ffi = (self._read_reg(self._REG_CDC_CONFIG) >> 6) & 0x03
        autoconfig0 = ((ffi & 0x03) << 6) | ((retry & 0x03) << 4) | (0x08 if are else 0) | (0x01 if ace else 0)
        self._write_reg(self._REG_AUTOCONFIG0, autoconfig0)
        autoconfig1 = (0x80 if scts else 0) | (0x04 if (are and False) else 0) | \
                      (0x02 if False else 0) | (0x01 if False else 0)
        self._write_reg(self._REG_AUTOCONFIG1, autoconfig1)

    def proximity_touched(self):
        """Return True if the ELEPROX virtual electrode is touched.

        ELEPROX must be enabled via start(eleprox_en>0) for this to be
        meaningful; otherwise the chip never reports ELEPROX touches.
        """
        raw = self._connection.write_read(bytes([self._REG_ELE8_PROX_TCH]), 1)
        return bool(raw[0] & 0x10)

    def clear_overcurrent(self):
        """Clear the OVCF bit in register 0x01.

        OVCF forces the chip into Stop Mode; clearing it is required
        before the chip can re-enter Run Mode after an over-current event.
        """
        raw = self._read_reg(self._REG_ELE8_PROX_TCH)
        self._write_reg(self._REG_ELE8_PROX_TCH, raw & 0x7F)

    def enable_interrupt(self, source):
        """Enable one of the AUTOCONFIG1-based interrupt sources.

        Args:
            source: One of Mpr121Full.SOURCE_OOR, SOURCE_ARF, SOURCE_ACF.
        """
        cur = self._read_reg(self._REG_AUTOCONFIG1) & 0x00
        self._write_reg(self._REG_AUTOCONFIG1, cur | (source & 0x07))

    def disable_interrupt(self, source):
        """Disable one of the AUTOCONFIG1-based interrupt sources."""
        cur = self._read_reg(self._REG_AUTOCONFIG1)
        self._write_reg(self._REG_AUTOCONFIG1, cur & ~(source & 0x07))

    def on_interrupt(self, callback):
        """Subscribe to INT-line assertions.

        The callback receives one argument: a 13-bit bitmask of the touch
        status (bit 0-11 = ELE0-ELE11, bit 12 = ELEPROX). Reading the
        status registers clears the chip's INT output.

        Wires connection.int_pin.on_edge() when an InputPin is configured;
        otherwise falls back to a 5 ms polling thread (Linux only).

        Args:
            callback: Callable(mask: int) invoked on each touch/release event.
        """
        self._callback = callback
        int_pin = self._connection.int_pin
        if int_pin is not None:
            from periph.connection.input_pin import InputPin
            int_pin.on_edge(self._int_handler, InputPin.FALLING)
        else:
            try:
                import _thread as _thr
                import time as _time
                self._poll_stop = False
                def _loop():
                    while not self._poll_stop:
                        m = self.poll_interrupt()
                        if m and self._callback:
                            self._callback(m)
                        _time.sleep(0.005)
                self._poll_thread = _thr.start_new_thread(_loop, ())
            except ImportError:
                try:
                    import threading
                    self._poll_stop = False
                    self._poll_thread = threading.Thread(target=self._poll_loop, daemon=True)
                    self._poll_thread.start()
                except Exception:
                    pass

    def _poll_loop(self):
        import time
        while not self._poll_stop:
            m = self.poll_interrupt()
            if m and self._callback:
                self._callback(m)
            time.sleep(0.005)

    def off_interrupt(self):
        """Unsubscribe and stop delivery."""
        int_pin = self._connection.int_pin
        if int_pin is not None:
            int_pin.off_edge(self._int_handler)
        else:
            self._poll_stop = True
        self._callback = None

    def _int_handler(self):
        m = self.poll_interrupt()
        if m and self._callback:
            self._callback(m)

    def poll_interrupt(self):
        """Read ELE0_7_TOUCH and ELE8_PROX_TOUCH as a 13-bit bitmask.

        Performs the two-byte I²C read that also clears the chip's INT
        output. The returned bitmask has bit n = 1 if ELEn is touched,
        bit 12 = 1 if ELEPROX is touched.

        Returns:
            int: 13-bit touched bitmask.
        """
        raw = self._connection.write_read(bytes([self._REG_ELE0_7_TOUCH]), 2)
        return raw[0] | ((raw[1] & 0x1F) << 8)
