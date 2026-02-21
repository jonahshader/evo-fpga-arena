package evo.ga

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import evo.types._

/**
 * Fitness evaluation FSM.
 *
 * Ported from fpga/src/fitness.vhd
 *
 * Evaluates fitness for each chromosome by playing games against:
 * - Model history opponents
 * - Reference opponents
 *
 * Nested loop structure:
 * - For each chromosome in population
 *   - For each opponent (model history + references)
 *     - For each seed
 *       - Play game without swap (p1 vs p2)
 *       - Play game with swap (p2 vs p1)
 *
 * The FSM waits for BRAM manager to load NN weights and for playagame to complete.
 */
class Fitness extends Component {
  val io = new Bundle {
    val rng = in Bits(32 bits)

    // Interface with bram_manager (bm)
    val bmCommand    = out(BramCommand())
    val bmReadIndex  = out UInt(8 bits)
    val bmGo         = out Bool()
    val bmDone       = in Bool()

    // Interface with GA controller
    val gaConfig       = in(GaConfigBundle())
    val fitnessGo      = in Bool()
    val fitnessDone    = out Bool()

    // Interface with playagame
    val seed           = out Bits(32 bits)
    val initPlayagame  = out Bool()
    val swapStart      = out Bool()

    val playagameDone = in Bool()
    val gameScore     = in SInt(16 bits)

    // Results
    val outputPopulationFitness = out Vec(SInt(16 bits), GaConfig.MAX_POPULATION_SIZE)
    val referenceFitnessSum     = out SInt(16 bits)
  }

  // --- Internal Registers ---
  val seedsArray = Vec(Reg(Bits(32 bits)) init 0, GaConfig.MAX_SEED_COUNT)

  val currentChromosome = Reg(UInt(8 bits)) init 0
  val currentOpponent   = Reg(UInt(8 bits)) init 0
  val seedCtr           = Reg(UInt(GaConfig.SEED_COUNT_BITS bits)) init 0
  val swapState         = Reg(Bool()) init False

  val currentNn1Index = Reg(UInt(8 bits)) init 0
  val currentNn2Index = Reg(UInt(8 bits)) init 0

  val fitnessAccumulator = Reg(SInt(16 bits)) init 0

  val seedRng      = Reg(Bits(32 bits)) init 0
  val seedInitCtr  = Reg(UInt(GaConfig.SEED_COUNT_BITS bits)) init 0

  // --- Computed Values ---
  val populationSize = (U(1, 8 bits) << io.gaConfig.populationSizeExp.resize(8)).resized
  val totalOpponents = io.gaConfig.modelHistorySize + io.gaConfig.referenceCount
  val opponentStart  = populationSize
  val nn1End         = populationSize - 1

  val referenceStart = populationSize + io.gaConfig.modelHistorySize
  val referenceEnd   = populationSize + io.gaConfig.modelHistorySize + io.gaConfig.referenceCount - 1

  val isReferenceOpponent = (currentOpponent >= referenceStart) && (currentOpponent <= referenceEnd)

  // --- Default Outputs ---
  io.bmCommand := BramCommand.READ_TO_NN_1
  io.bmReadIndex := 0
  io.bmGo := False
  io.fitnessDone := False
  io.seed := 0
  io.initPlayagame := False
  io.swapStart := False

  // Initialize output fitness array
  for (i <- 0 until GaConfig.MAX_POPULATION_SIZE) {
    io.outputPopulationFitness(i) := 0
  }

  // --- FSM ---
  val fsm = new StateMachine {
    val idle       = new State with EntryPoint
    val initSeeds  = new State
    val checkNn1   = new State
    val waitNn1    = new State
    val checkNn2   = new State
    val waitNn2    = new State
    val startGame  = new State
    val waitGame   = new State
    val accumulate = new State
    val advance    = new State
    val done       = new State

    idle.whenIsActive {
      when(io.fitnessGo) {
        currentChromosome := 0
        currentOpponent := populationSize
        seedCtr := 0
        swapState := False
        fitnessAccumulator := 0

        when(io.gaConfig.recycleSeeds) {
          seedRng := io.gaConfig.seed
        } otherwise {
          seedRng := io.rng
        }

        seedInitCtr := 0
        goto(initSeeds)
      }
    }

    initSeeds.whenIsActive {
      seedsArray(seedInitCtr) := seedRng
      seedRng := (seedRng.asUInt + 1).asBits
      when(seedInitCtr === (io.gaConfig.seedCount - 1).resized) {
        goto(checkNn1)
      } otherwise {
        seedInitCtr := seedInitCtr + 1
      }
    }

    checkNn1.whenIsActive {
      when(currentChromosome =/= currentNn1Index) {
        io.bmCommand := BramCommand.READ_TO_NN_1
        io.bmReadIndex := currentChromosome
        io.bmGo := True
        goto(waitNn1)
      } otherwise {
        goto(checkNn2)
      }
    }

    waitNn1.whenIsActive {
      when(io.bmDone) {
        currentNn1Index := currentChromosome
        goto(checkNn2)
      }
    }

    checkNn2.whenIsActive {
      when(currentOpponent =/= currentNn2Index) {
        io.bmCommand := BramCommand.READ_TO_NN_2
        io.bmReadIndex := currentOpponent
        io.bmGo := True
        goto(waitNn2)
      } otherwise {
        goto(startGame)
      }
    }

    waitNn2.whenIsActive {
      when(io.bmDone) {
        currentNn2Index := currentOpponent
        goto(startGame)
      }
    }

    startGame.whenIsActive {
      io.initPlayagame := True
      io.seed := seedsArray(seedCtr)
      io.swapStart := swapState
      goto(waitGame)
    }

    waitGame.whenIsActive {
      when(io.playagameDone) {
        goto(accumulate)
      }
    }

    accumulate.whenIsActive {
      fitnessAccumulator := fitnessAccumulator + io.gameScore
      goto(advance)
    }

    advance.whenIsActive {
      when(!swapState) {
        swapState := True
        goto(startGame)
      }.elsewhen(seedCtr < (io.gaConfig.seedCount - 1).resized) {
        seedCtr := seedCtr + 1
        swapState := False
        goto(startGame)
      }.elsewhen(currentOpponent < (opponentStart + totalOpponents - 1).resized) {
        currentOpponent := currentOpponent + 1
        seedCtr := 0
        swapState := False
        goto(checkNn2)
      }.elsewhen(currentChromosome < nn1End) {
        io.outputPopulationFitness(currentChromosome.resize(log2Up(GaConfig.MAX_POPULATION_SIZE))) := fitnessAccumulator
        currentChromosome := currentChromosome + 1
        currentOpponent := populationSize
        seedCtr := 0
        swapState := False
        fitnessAccumulator := 0
        goto(checkNn1)
      }.otherwise {
        io.outputPopulationFitness(currentChromosome.resize(log2Up(GaConfig.MAX_POPULATION_SIZE))) := fitnessAccumulator
        io.fitnessDone := True
        goto(done)
      }
    }

    done.whenIsActive {
      when(!io.fitnessGo) {
        goto(idle)
      }
    }
  }

  // Handle reference fitness sum accumulation
  val referenceFitnessSumReg = Reg(SInt(16 bits)) init 0

  val accumulating = fsm.isActive(fsm.accumulate)
  when(fsm.isActive(fsm.idle) && io.fitnessGo) {
    referenceFitnessSumReg := 0
  }.elsewhen(accumulating) {
    when(isReferenceOpponent) {
      referenceFitnessSumReg := referenceFitnessSumReg + io.gameScore
    }
  }

  io.referenceFitnessSum := referenceFitnessSumReg
}
