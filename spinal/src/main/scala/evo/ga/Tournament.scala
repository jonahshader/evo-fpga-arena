package evo.ga

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import evo.types._

/**
 * Tournament selection FSM.
 *
 * Runs tournament selection on a population:
 * - For each chromosome in the population, run a tournament
 * - Each tournament examines tournament_size candidates (via RNG)
 * - Tracks winner counts for all chromosomes
 *
 * Port mapping from tournament.vhd:
 * - clk   : clock
 * - rng   : 32-bit random number input
 * - go    : start/re-init pulse
 * - ga_config : configuration (tournament_size, population_size_exp)
 * - input_population_fitness : fitness array for all chromosomes
 * - winner_counts : output array tracking win counts
 * - done : indicates tournament completion
 */
class Tournament extends Component {
  val io = new Bundle {
    val rng                      = in Bits(32 bits)
    val go                       = in Bool()
    val ga_config                = in(GaConfigBundle())
    val input_population_fitness = in(Vec(SInt(16 bits), GaConfig.MAX_POPULATION_SIZE))
    val winner_counts            = out(Vec(UInt(8 bits), GaConfig.MAX_POPULATION_SIZE))
    val done                     = out Bool()
  }

  // Calculate population size from exponent: 2^population_size_exp
  def calcPopulationSize(exp: UInt): UInt = {
    // Left shift 1 by exp to get 2^exp
    // exp is 8 bits, max population size is 128 (2^7)
    // We need to handle shift amount carefully
    val result = UInt(8 bits)
    // Case statement for each possible exponent value (0-7, clamped to max)
    result := Mux(exp === 0, U(1, 8 bits),
             Mux(exp === 1, U(2, 8 bits),
             Mux(exp === 2, U(4, 8 bits),
             Mux(exp === 3, U(8, 8 bits),
             Mux(exp === 4, U(16, 8 bits),
             Mux(exp === 5, U(32, 8 bits),
             Mux(exp === 6, U(64, 8 bits),
             Mux(exp === 7, U(128, 8 bits),
             U(128, 8 bits)))))))))  // exp > 7 also gets max
    result
  }

  // --- Registers ---
  val count            = Reg(UInt(8 bits)) init(0)
  val tournamentRound  = Reg(UInt(8 bits)) init(0)
  val bestScore        = Reg(SInt(16 bits)) init(0)
  val bestIndex        = Reg(UInt(7 bits)) init(0)

  // Winner counts are registered
  val winnerCountsReg = Vec(Reg(UInt(8 bits)) init(0), GaConfig.MAX_POPULATION_SIZE)

  // Combinational current index from RNG (7 bits for max 128 population)
  val currentIndex = io.rng(6 downto 0).asUInt

  // Fitness candidate at current index
  val fitnessCandidate = io.input_population_fitness(currentIndex)

  // Connect output
  io.winner_counts := winnerCountsReg

  // Population size calculation
  val populationSize = calcPopulationSize(io.ga_config.populationSizeExp)

  // --- FSM ---
  val fsm = new StateMachine {
    val idle             = new State with EntryPoint
    val tournamentRoundS = new State

    io.done := False

    idle.whenIsActive {
      when(io.go) {
        // Initialize all state
        count := 0
        tournamentRound := 0
        bestScore := 0
        bestIndex := 0

        // Reset all winner counts
        for (i <- 0 until GaConfig.MAX_POPULATION_SIZE) {
          winnerCountsReg(i) := 0
        }

        goto(tournamentRoundS)
      }
    }

    tournamentRoundS.whenIsActive {
      // Update best score/index
      when((tournamentRound === 0) || (fitnessCandidate > bestScore)) {
        bestScore := fitnessCandidate
        bestIndex := currentIndex
      }

      // Check if tournament is complete (before incrementing tournamentRound)
      val isTournamentComplete = tournamentRound === (io.ga_config.tournamentSize - 1)

      when(isTournamentComplete) {
        // Check if all tournaments are complete (BEFORE incrementing count)
        val allDone = count === (populationSize - 1)

        // End of tournament: update win count for the winner
        // Use the potentially updated bestIndex from this cycle
        val finalBestIndex = Mux(
          (tournamentRound === 0) || (fitnessCandidate > bestScore),
          currentIndex,
          bestIndex
        )
        winnerCountsReg(finalBestIndex) := winnerCountsReg(finalBestIndex) + 1
        count := count + 1
        tournamentRound := 0
        // Reset best_score for next tournament to first candidate's fitness
        bestScore := fitnessCandidate

        when(allDone) {
          io.done := True
          goto(idle)
        }
      } otherwise {
        // Just continue to next round
        tournamentRound := tournamentRound + 1
      }
    }
  }
}
