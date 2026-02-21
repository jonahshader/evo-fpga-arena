package evo.infra

import spinal.core._
import spinal.lib._

/**
 * Simple dual-port Block RAM wrapper.
 *
 * Port A: Write-only (we_a, addr_a, din_a)
 * Port B: Read-only (en_b, addr_b, dout_b)
 *
 * Mirrors the VHDL bram_sdp.vhd entity for API parity.
 * Uses SpinalHDL's Mem which synthesizes to BRAM on FPGA.
 *
 * This is a single-clock implementation suitable for most use cases.
 * The component uses the system clock domain.
 *
 * @param width     Bit width of each entry (default 36)
 * @param depth     Number of entries (default 1024)
 */
case class BramSdpConfig(
  width: Int = 36,
  depth: Int = 1024
) {
  def addrBits: Int = log2Up(depth)
}

class BramSdp(config: BramSdpConfig) extends Component {
  val io = new Bundle {
    // Port A - Write only
    val weA   = in Bool()
    val addrA = in UInt(config.addrBits bits)
    val dinA  = in Bits(config.width bits)

    // Port B - Read only
    val enB   = in Bool()
    val addrB = in UInt(config.addrBits bits)
    val doutB = out Bits(config.width bits)
  }

  // Memory
  val mem = Mem(Bits(config.width bits), config.depth)

  // Write logic - write when weA is high
  when(io.weA) {
    mem(io.addrA) := io.dinA
  }

  // Read logic - registered output with read enable
  val doutReg = Reg(Bits(config.width bits)) init(0)

  when(io.enB) {
    doutReg := mem(io.addrB)
  }

  io.doutB := doutReg
}

/** Factory for default BramSdp configuration. */
object BramSdp {
  def apply(width: Int = 36, depth: Int = 1024): BramSdp = {
    new BramSdp(BramSdpConfig(width, depth))
  }
}
