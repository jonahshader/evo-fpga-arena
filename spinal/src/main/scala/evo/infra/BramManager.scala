package evo.infra

import spinal.core._
import spinal.lib._
import evo.types._
import evo.nn.Mutate

/**
 * BRAM Manager — houses all BRAMs storing neural network parameters.
 *
 * Ported from fpga/src/bram_manager.vhd
 *
 * Operations:
 * - COPY_AND_MUTATE: read from read_index BRAM, mutate, write to write_index BRAM
 * - READ_TO_NN_1/2: stream all params from read_index BRAM to NN 1/2
 * - DUMP: stream all params from read_index BRAM (for debug/comms)
 *
 * Instantiates NUM_BRAMS BramSdp instances.
 * Port A: write (shared address/data, per-BRAM write-enable)
 * Port B: read (shared address = param_index, muxed output by read_index)
 */
class BramManager extends Component {
  import BramConfig._

  val io = new Bundle {
    val command      = in(BramCommand())
    val readIndex    = in UInt(8 bits)
    val writeIndex   = in UInt(8 bits)
    val rng          = in Bits(32 bits)
    val mutationRate = in UInt(8 bits)

    val param          = out Bits(BRAM_WIDTH bits)
    val paramIndex     = out UInt(BRAM_ADDR_BITS bits)
    val paramValidNn1  = out Bool()
    val paramValidNn2  = out Bool()
    val paramValidDump = out Bool()

    val go   = in Bool()
    val done = out Bool()
  }

  // --- Internal registers ---
  val weAArr = Vec(Reg(Bool()) init False, NUM_BRAMS)

  val addrA = Reg(UInt(BRAM_ADDR_BITS bits)) init 0
  val dinA  = Bits(BRAM_WIDTH bits)

  val commandR      = Reg(BramCommand()) init BramCommand.COPY_AND_MUTATE
  val readIndexR    = Reg(UInt(8 bits)) init 0
  val writeIndexR   = Reg(UInt(8 bits)) init 0
  val mutationRateR = Reg(UInt(8 bits)) init 0

  val paramIndexReg    = Reg(UInt(BRAM_ADDR_BITS bits)) init 0
  val paramIndexDelay  = Reg(UInt(BRAM_ADDR_BITS bits)) init 0

  val doneR = Reg(Bool()) init True

  // --- BRAM instances ---
  val doutBArr = Vec(Bits(BRAM_WIDTH bits), NUM_BRAMS)

  for (i <- 0 until NUM_BRAMS) {
    val bram = BramSdp(width = BRAM_WIDTH, depth = BRAM_DEPTH)

    // Port A: write
    bram.io.weA   := weAArr(i)
    bram.io.addrA := addrA
    bram.io.dinA  := dinA

    // Port B: read
    bram.io.enB   := True
    bram.io.addrB := paramIndexReg
    doutBArr(i)   := bram.io.doutB
  }

  // --- Continuous outputs ---
  io.param          := doutBArr(readIndexR)
  io.paramValidNn1  := commandR === BramCommand.READ_TO_NN_1 && !doneR
  io.paramValidNn2  := commandR === BramCommand.READ_TO_NN_2 && !doneR
  io.paramValidDump := commandR === BramCommand.DUMP && !doneR
  io.done           := doneR && !io.go
  io.paramIndex     := paramIndexReg

  // --- Mutation: din_a is the mutated param ---
  dinA := Mutate.mutateParam(io.param, paramIndexReg, io.rng, mutationRateR)

  // --- Main process ---
  when(True) {
    paramIndexDelay := paramIndexReg

    when(doneR) {
      // Not running, able to accept command
      when(io.go) {
        doneR         := False
        commandR      := io.command
        readIndexR    := io.readIndex
        writeIndexR   := io.writeIndex
        mutationRateR := io.mutationRate
        paramIndexReg := 0
        addrA         := 0
        for (i <- 0 until NUM_BRAMS) {
          weAArr(i) := False
        }
      }
    } otherwise {
      // Running
      when(commandR === BramCommand.COPY_AND_MUTATE) {
        when(paramIndexReg < NnConfig.TOTAL_PARAMS - 1) {
          paramIndexReg := paramIndexReg + 1
        } otherwise {
          paramIndexReg := 0
          doneR := True
        }
        addrA := paramIndexDelay
        for (i <- 0 until NUM_BRAMS) {
          weAArr(i) := writeIndexR === i
        }
      } otherwise {
        // READ_TO_NN_1, READ_TO_NN_2, DUMP
        when(paramIndexReg < NnConfig.TOTAL_PARAMS - 1) {
          paramIndexReg := paramIndexReg + 1
        } otherwise {
          paramIndexReg := 0
          doneR := True
        }
      }
    }
  }
}
