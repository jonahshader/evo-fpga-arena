package evo.ga

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import evo.types._

/**
 * VictorCopy — Selective reproduction FSM.
 *
 * Pairs multi-victors (organisms with 2+ wins) with non-victors (0 wins),
 * then issues COPY_AND_MUTATE commands to the bram_manager.
 *
 * Ported from fpga/src/victor_copy.vhd
 */
class VictorCopy extends Component {
  val io = new Bundle {
    // Config
    val config = in(GaConfigBundle())

    // Tournament I/O
    val winnerCounts = in(Vec(UInt(8 bits), GaConfig.MAX_POPULATION_SIZE))

    // BRAM manager I/O
    val command           = out(BramCommand())
    val readIndex         = out UInt(8 bits)
    val writeIndex        = out UInt(8 bits)
    val mutationRate      = out UInt(8 bits)
    val bramManagerGo     = out Bool()
    val bramManagerDone   = in Bool()

    // Control
    val go   = in Bool()
    val done = out Bool()
  }

  // Default output assignments
  io.command := BramCommand.COPY_AND_MUTATE
  io.readIndex := 0
  io.writeIndex := 0
  io.mutationRate := 0
  io.bramManagerGo := False
  io.done := True

  // --- Registered winner counts (modified during operation) ---
  // Each element is initialized to 0 and assigned only in the FSM
  val winnerCountsR = Vec(Reg(UInt(8 bits)) init(0), GaConfig.MAX_POPULATION_SIZE)

  // --- Index registers ---
  val readIndexReg  = Reg(UInt(8 bits)) init(0)
  val writeIndexReg = Reg(UInt(8 bits)) init(0)

  // --- Computed population size ---
  val popSize = U(1, 8 bits) << io.config.populationSizeExp.resize(8)

  // --- FSM ---
  val fsm = new StateMachine {
    val idleS        = new State with EntryPoint
    val loadS        = new State
    val seekingPtrsS = new State
    val copyS        = new State

    // Idle state: wait for go signal
    idleS.whenIsActive {
      io.done := True
      when(io.go) {
        // Reset indices
        readIndexReg  := 0
        writeIndexReg := 0
        io.done := False
        goto(loadS)
      }
    }

    // Load state: capture winner counts, then go to seeking
    loadS.whenIsActive {
      io.done := False  // Not done while loading
      // Register the winner counts (captured on next cycle)
      for (i <- 0 until GaConfig.MAX_POPULATION_SIZE) {
        winnerCountsR(i) := io.winnerCounts(i)
      }
      goto(seekingPtrsS)
    }

    // Seeking state: find a multi-victor for read and a non-victor for write
    seekingPtrsS.whenIsActive {
      io.done := False  // Not done while seeking
      io.readIndex  := readIndexReg
      io.writeIndex := writeIndexReg

      when(readIndexReg < popSize) {
        // Check conditions (resize index to 7 bits for Vec access)
        val readIdx = readIndexReg.resized
        val writeIdx = writeIndexReg.resized
        val readIndexOnMultiVictor = winnerCountsR(readIdx) >= 2
        val writeIndexOnNonVictor  = winnerCountsR(writeIdx) === 0

        // Advance read index if not on a multi-victor
        when(!readIndexOnMultiVictor) {
          readIndexReg := readIndexReg + 1
        }

        // Advance write index if not on a non-victor
        when(!writeIndexOnNonVictor) {
          when(writeIndexReg === (popSize - 1)) {
            writeIndexReg := 0
          } otherwise {
            writeIndexReg := writeIndexReg + 1
          }
        }

        // When both conditions are satisfied, initiate copy
        when(readIndexOnMultiVictor && writeIndexOnNonVictor) {
          // Set up the copy operation
          io.mutationRate := io.config.mutationRates(writeIdx)
          io.command      := BramCommand.COPY_AND_MUTATE
          io.bramManagerGo := True

          // Increment non-victor count to 1 (we know it's 0, so just set to 1)
          winnerCountsR(writeIdx) := U(1, 8 bits)

          // Decrement the victor count we're copying from
          winnerCountsR(readIdx) := winnerCountsR(readIdx) - 1

          goto(copyS)
        }
      } otherwise {
        // Reached the end, we're done
        io.done := True
        readIndexReg  := 0
        writeIndexReg := 0
        goto(idleS)
      }
    }

    // Copy state: wait for bram_manager to finish the copy
    copyS.whenIsActive {
      io.done := False  // Not done while copying
      io.readIndex  := readIndexReg
      io.writeIndex := writeIndexReg
      io.bramManagerGo := False

      when(io.bramManagerDone) {
        goto(seekingPtrsS)
      }
    }
  }
}
