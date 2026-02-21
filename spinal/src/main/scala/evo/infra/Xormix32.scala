package evo.infra

import spinal.core._

/**
 * XORmix32 Random Number Generator.
 *
 * Ported from fpga/src/imports/xormix32.vhd
 *
 * XORmix32 is a nonlinear PRNG designed for hardware. It maintains a 32-bit X state
 * and N 32-bit Y states (where N = streamCount). Each enable pulse advances the generator
 * and produces new random values. A reset pulse loads the seed values.
 *
 * @param streamCount Number of parallel RNG streams (1-32).
 */
class Xormix32(streamCount: Int = 1) extends Component {
  require(streamCount >= 1 && streamCount <= 32, "streamCount must be between 1 and 32")

  val io = new Bundle {
    val rst    = in Bool()

    val seedX  = in Bits(32 bits)
    val seedY  = in Bits(32 * streamCount bits)

    val enable = in Bool()
    val result = out Bits(32 * streamCount bits)
  }

  // State registers
  val stateX = Reg(Bits(32 bits)) init 0
  val stateY = Reg(Bits(32 * streamCount bits)) init 0

  // --- Combinational next-state computation ---
  // State X update (linear feedback)
  val x = stateX
  val nextX = Bits(32 bits)

  nextX(0)  := x(11) ^ x(24) ^ x(22) ^ x(3)  ^ x(19)
  nextX(1)  := x(25) ^ x(7)  ^ x(20) ^ x(2)  ^ x(26) ^ x(28)
  nextX(2)  := x(8)  ^ x(5)  ^ x(18) ^ x(24) ^ x(4)
  nextX(3)  := x(8)  ^ x(22) ^ x(26) ^ x(7)  ^ x(21) ^ x(14)
  nextX(4)  := x(30) ^ x(26) ^ x(25) ^ x(14) ^ x(24)
  nextX(5)  := x(21) ^ x(10) ^ x(16) ^ x(13) ^ x(5)  ^ x(17)
  nextX(6)  := x(14) ^ x(29) ^ x(24) ^ x(11) ^ x(25)
  nextX(7)  := x(5)  ^ x(26) ^ x(31) ^ x(22) ^ x(27) ^ x(7)
  nextX(8)  := x(0)  ^ x(17) ^ x(1)  ^ x(18) ^ x(8)
  nextX(9)  := x(29) ^ x(0)  ^ x(21) ^ x(26) ^ x(3)  ^ x(13)
  nextX(10) := x(23) ^ x(29) ^ x(19) ^ x(21) ^ x(10)
  nextX(11) := x(19) ^ x(20) ^ x(4)  ^ x(18) ^ x(15) ^ x(10)
  nextX(12) := x(28) ^ x(29) ^ x(24) ^ x(19) ^ x(4)
  nextX(13) := x(19) ^ x(6)  ^ x(27) ^ x(12) ^ x(11) ^ x(7)
  nextX(14) := x(1)  ^ x(5)  ^ x(3)  ^ x(30) ^ x(25)
  nextX(15) := x(22) ^ x(12) ^ x(11) ^ x(7)  ^ x(28) ^ x(1)
  nextX(16) := x(16) ^ x(5)  ^ x(29) ^ x(2)  ^ x(14)
  nextX(17) := x(8)  ^ x(24) ^ x(0)  ^ x(23) ^ x(31) ^ x(26)
  nextX(18) := x(15) ^ x(17) ^ x(4)  ^ x(9)  ^ x(6)
  nextX(19) := x(30) ^ x(9)  ^ x(18) ^ x(2)  ^ x(11) ^ x(6)
  nextX(20) := x(2)  ^ x(27) ^ x(15) ^ x(12) ^ x(20)
  nextX(21) := x(21) ^ x(20) ^ x(10) ^ x(6)  ^ x(31) ^ x(1)
  nextX(22) := x(9)  ^ x(29) ^ x(15) ^ x(27) ^ x(16)
  nextX(23) := x(29) ^ x(10) ^ x(31) ^ x(30) ^ x(13) ^ x(3)
  nextX(24) := x(31) ^ x(23) ^ x(6)  ^ x(24) ^ x(17)
  nextX(25) := x(4)  ^ x(8)  ^ x(6)  ^ x(19) ^ x(16) ^ x(9)
  nextX(26) := x(23) ^ x(22) ^ x(15) ^ x(28) ^ x(6)
  nextX(27) := x(30) ^ x(9)  ^ x(10) ^ x(28) ^ x(18) ^ x(15)
  nextX(28) := x(25) ^ x(20) ^ x(19) ^ x(12) ^ x(28)
  nextX(29) := x(13) ^ x(10) ^ x(9)  ^ x(8)  ^ x(0)  ^ x(14)
  nextX(30) := x(22) ^ x(27) ^ x(3)  ^ x(13) ^ x(23)
  nextX(31) := x(12) ^ x(2)  ^ x(16) ^ x(1)  ^ x(17) ^ x(23)

  // Salt constants
  val salts = Vec(Bits(32 bits), 32)
  salts(0)  := B"32'h198F8D32"
  salts(1)  := B"32'h46D9B8AC"
  salts(2)  := B"32'h57F90206"
  salts(3)  := B"32'hCB246290"
  salts(4)  := B"32'h5FDA94C2"
  salts(5)  := B"32'hB9969E83"
  salts(6)  := B"32'h990053FE"
  salts(7)  := B"32'h0CEF1F8B"
  salts(8)  := B"32'h9BAAFEFA"
  salts(9)  := B"32'h232B8463"
  salts(10) := B"32'h0FC77197"
  salts(11) := B"32'hD113A2D8"
  salts(12) := B"32'hD6C99EF7"
  salts(13) := B"32'hF3FB7189"
  salts(14) := B"32'h9CEEB1DD"
  salts(15) := B"32'h352DF180"
  salts(16) := B"32'hFEED780C"
  salts(17) := B"32'hEE211518"
  salts(18) := B"32'h3AFACA18"
  salts(19) := B"32'h95F13C50"
  salts(20) := B"32'hD8449F2A"
  salts(21) := B"32'h59752549"
  salts(22) := B"32'h854F0980"
  salts(23) := B"32'h234A07B4"
  salts(24) := B"32'h51C0C69B"
  salts(25) := B"32'hA71D489E"
  salts(26) := B"32'h618CBC79"
  salts(27) := B"32'hAB0E51E1"
  salts(28) := B"32'h965C4507"
  salts(29) := B"32'hE90488A4"
  salts(30) := B"32'h73674EB7"
  salts(31) := B"32'h00AF1456"

  // State Y update (nonlinear mixing) - 2-phase
  val intermediateY = Vec(Bits(32 bits), streamCount)
  val finalY = Vec(Bits(32 bits), streamCount)

  // First pass: compute intermediate Y values
  for (i <- 0 until streamCount) {
    val mixin = x ^ salts(i)
    val nextStreamIdx = (i + 1) % streamCount
    val mixup = stateY(32 * nextStreamIdx + 31 downto 32 * nextStreamIdx)
    val currentYHigh = stateY(32 * i + 31 downto 32 * i + 16)
    val res = Bits(16 bits)

    def mixinBit(offset: Int): Bool = mixin((i + offset) % 32)

    res(0)  := mixup(0)  ^ (mixup(6)  & ~mixup(16)) ^ mixup(9)  ^ mixup(15) ^ mixinBit(15)
    res(1)  := mixup(1)  ^ (mixup(7)  & ~mixup(17)) ^ mixup(10) ^ mixup(16) ^ mixinBit(29)
    res(2)  := mixup(2)  ^ (mixup(8)  & ~mixup(18)) ^ mixup(11) ^ mixup(17) ^ mixinBit(5)
    res(3)  := mixup(3)  ^ (mixup(9)  & ~mixup(19)) ^ mixup(12) ^ mixup(18) ^ mixinBit(0)
    res(4)  := mixup(4)  ^ (mixup(10) & ~mixup(20)) ^ mixup(13) ^ mixup(19) ^ mixinBit(16)
    res(5)  := mixup(5)  ^ (mixup(11) & ~mixup(21)) ^ mixup(14) ^ mixup(20) ^ mixinBit(9)
    res(6)  := mixup(6)  ^ (mixup(12) & ~mixup(22)) ^ mixup(15) ^ mixup(21) ^ mixinBit(26)
    res(7)  := mixup(7)  ^ (mixup(13) & ~mixup(23)) ^ mixup(16) ^ mixup(22) ^ mixinBit(14)
    res(8)  := mixup(8)  ^ (mixup(14) & ~mixup(24)) ^ mixup(17) ^ mixup(23) ^ mixinBit(13)
    res(9)  := mixup(9)  ^ (mixup(15) & ~mixup(25)) ^ mixup(18) ^ mixup(24) ^ mixinBit(10)
    res(10) := mixup(10) ^ (mixup(16) & ~mixup(26)) ^ mixup(19) ^ mixup(25) ^ mixinBit(19)
    res(11) := mixup(11) ^ (mixup(17) & ~mixup(27)) ^ mixup(20) ^ mixup(26) ^ mixinBit(11)
    res(12) := mixup(12) ^ (mixup(18) & ~mixup(28)) ^ mixup(21) ^ mixup(27) ^ mixinBit(2)
    res(13) := mixup(13) ^ (mixup(19) & ~mixup(29)) ^ mixup(22) ^ mixup(28) ^ mixinBit(6)
    res(14) := mixup(14) ^ (mixup(20) & ~mixup(30)) ^ mixup(23) ^ mixup(29) ^ mixinBit(8)
    res(15) := mixup(15) ^ (mixup(21) & ~mixup(31)) ^ mixup(24) ^ mixup(30) ^ mixinBit(17)

    intermediateY(i) := currentYHigh ## res
  }

  // Second pass: compute final Y values
  for (i <- 0 until streamCount) {
    val mixin = x ^ salts(i)
    val mixup = intermediateY((i + 1) % streamCount)
    val intermediateYHigh = intermediateY(i)(31 downto 16)
    val res = Bits(16 bits)

    def mixinBit2(offset: Int): Bool = mixin((i + offset) % 32)

    res(0)  := mixup(0)  ^ (mixup(6)  & ~mixup(16)) ^ mixup(9)  ^ mixup(15) ^ mixinBit2(20)
    res(1)  := mixup(1)  ^ (mixup(7)  & ~mixup(17)) ^ mixup(10) ^ mixup(16) ^ mixinBit2(4)
    res(2)  := mixup(2)  ^ (mixup(8)  & ~mixup(18)) ^ mixup(11) ^ mixup(17) ^ mixinBit2(22)
    res(3)  := mixup(3)  ^ (mixup(9)  & ~mixup(19)) ^ mixup(12) ^ mixup(18) ^ mixinBit2(30)
    res(4)  := mixup(4)  ^ (mixup(10) & ~mixup(20)) ^ mixup(13) ^ mixup(19) ^ mixinBit2(31)
    res(5)  := mixup(5)  ^ (mixup(11) & ~mixup(21)) ^ mixup(14) ^ mixup(20) ^ mixinBit2(21)
    res(6)  := mixup(6)  ^ (mixup(12) & ~mixup(22)) ^ mixup(15) ^ mixup(21) ^ mixinBit2(24)
    res(7)  := mixup(7)  ^ (mixup(13) & ~mixup(23)) ^ mixup(16) ^ mixup(22) ^ mixinBit2(25)
    res(8)  := mixup(8)  ^ (mixup(14) & ~mixup(24)) ^ mixup(17) ^ mixup(23) ^ mixinBit2(18)
    res(9)  := mixup(9)  ^ (mixup(15) & ~mixup(25)) ^ mixup(18) ^ mixup(24) ^ mixinBit2(27)
    res(10) := mixup(10) ^ (mixup(16) & ~mixup(26)) ^ mixup(19) ^ mixup(25) ^ mixinBit2(28)
    res(11) := mixup(11) ^ (mixup(17) & ~mixup(27)) ^ mixup(20) ^ mixup(26) ^ mixinBit2(23)
    res(12) := mixup(12) ^ (mixup(18) & ~mixup(28)) ^ mixup(21) ^ mixup(27) ^ mixinBit2(12)
    res(13) := mixup(13) ^ (mixup(19) & ~mixup(29)) ^ mixup(22) ^ mixup(28) ^ mixinBit2(7)
    res(14) := mixup(14) ^ (mixup(20) & ~mixup(30)) ^ mixup(23) ^ mixup(29) ^ mixinBit2(1)
    res(15) := mixup(15) ^ (mixup(21) & ~mixup(31)) ^ mixup(24) ^ mixup(30) ^ mixinBit2(3)

    finalY(i) := intermediateYHigh ## res
  }

  val nextY = Bits(32 * streamCount bits)
  for (i <- 0 until streamCount) {
    nextY(32 * i + 31 downto 32 * i) := finalY(i)
  }

  // --- Register update logic ---
  when(io.rst) {
    stateX := io.seedX
    stateY := io.seedY
  } elsewhen(io.enable) {
    stateX := nextX
    stateY := nextY
  }

  // Output is always the current stateY
  io.result := stateY
}

object Xormix32 {
  val MAX_STREAMS = 32
}
