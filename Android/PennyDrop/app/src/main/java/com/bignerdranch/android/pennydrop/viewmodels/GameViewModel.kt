package com.bignerdranch.android.pennydrop.viewmodels

import android.app.Application
import androidx.lifecycle.*
import com.bignerdranch.android.pennydrop.data.GameStatus
import com.bignerdranch.android.pennydrop.data.GameWithPlayers
import com.bignerdranch.android.pennydrop.data.PennyDropDatabase
import com.bignerdranch.android.pennydrop.data.PennyDropRepository
import com.bignerdranch.android.pennydrop.game.GameHandler
import com.bignerdranch.android.pennydrop.game.TurnEnd
import com.bignerdranch.android.pennydrop.game.TurnResult
import com.bignerdranch.android.pennydrop.types.Player
import com.bignerdranch.android.pennydrop.types.Slot
import com.bignerdranch.android.pennydrop.types.clear
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * @property currentPlayer - current player in the game
 * @property canRoll - opportunity to the current player to make a move
 * @property canPass - opportunity to the current player to make a pass
 * @property currentTurnText - game's history
 * @property currentStandingsText - result of the game
 */
//AndroidViewModel(application) - parent class which allows us to refer to the Application
//to get the context in ViewModel
class GameViewModel(application: Application): AndroidViewModel(application) {

    private var clearText = false

    private val repository: PennyDropRepository

    //MediatorLiveData<>() - LiveData subclass which may observe other
    // LiveData objects and react on OnChanged events from them.
    val currentGame = MediatorLiveData<GameWithPlayers>()

    val currentGameStatuses: LiveData<List<GameStatus>>

    //current player in the game
//    val currentPlayer = MutableLiveData<Player?>()
    //Transformations - tolls for change LifeData when it changes
    //map() - Возвращает LiveData, сопоставленный с входным источником LiveData,
    // применяя mapFunction к каждому значению, установленному в источнике.
    val currentPlayer = Transformations.map(this.currentGame) { gameWithPlayers ->
        gameWithPlayers?.players?.firstOrNull { it.isRolling}
    }

    //result of the game
    var currentStandingsText = Transformations.map(this.currentGame) { gameWithPlayers ->
        gameWithPlayers?.players?.let { players ->
            this.generateCurrentStandings(players)
        }
    }

    private var players: List<Player> = emptyList()

    val slots = Transformations

    //opportunity to the current player to make a move
    val canRoll: LiveData<Boolean>

    //opportunity to the current player to make a pass
    val canPass: LiveData<Boolean>

    //game's history
    val currentTurnText = MutableLiveData("")

    init {

        //create database's variable to get repository's variable
        //viewModelScope - CoroutineScope tied to this ViewModel. This scope will be canceled
        // when ViewModel will be cleared, i.e ViewModel.onCleared is called
        this.repository = PennyDropDatabase.getDatabase(application, viewModelScope).pennyDropDao()
            .let { dao -> PennyDropRepository.getInstance(dao) }

        this.currentGameStatuses = this.repository.getCurrentGameStatuses()

        //addSource() - Starts to listen the given source LiveData, onChanged observer
        // will be called when source value was changed.
        //Params: source – the LiveData to listen to
        //onChanged – The observer that will receive the event
        this.currentGame.addSource(this.repository.getCurrentGameWithPlayers()) { gameWithPlayers ->
            updateCurrentGame(gameWithPlayers, this.currentGameStatuses.value)
        }

        //addSource() - Starts to listen the given source LiveData, onChanged observer
        // will be called when source value was changed.
        //Params: source – the LiveData to listen to
        //onChanged – The observer that will receive the event
        this.currentGame.addSource(this.currentGameStatuses) { gameStatuses ->
            updateCurrentGame(this.currentGame.value, gameStatuses)
        }
    }

    private fun updateCurrentGame(gameWithPlayers: GameWithPlayers?, gameStatuses: List<GameStatus>?) {
        this.currentGame.value = gameWithPlayers?.updateStatuses(gameStatuses)
    }

//    fun startGame(playersForNewGame: List<Player>) {
//
//        //getting the players fom PickPlayersViewModel
//        this.players = playersForNewGame
//        this.currentPlayer.value = this.players.firstOrNull().apply {
//            this?.isRolling = true
//        }
//
//        this.canRoll.value = true
//        this.canPass.value = false
//
//        slots.value?.clear()
//        slots.notifyChange()
//
//        currentTurnText.value = "The game has begun!\n"
//        currentStandingsText.value = generateCurrentStandings(this.players)
//    }

    //send players in database for starting the game
    suspend fun startGame(playersForNewGame: List<Player>) {
        repository.startGame(playersForNewGame)
    }

    /**
     * @since roll() - fun is called from fragment_game.xml, calls anther fun roll() from GameHandler
     */
    fun roll() {
        slots.value?.let { currentSlots ->

            //Comparing against true saves us a null check
            val currentPlayer = players.firstOrNull { it.isRolling }
            if (currentPlayer != null && canRoll.value == true) {
                updateFromGameHandler(GameHandler.roll(players, currentPlayer, currentSlots))
            }
        }
    }

    /**
     * @since pass() - fun is coled from fragment_game.xml, calls anther fun pass() from GameHandler
     */
    fun pass() {
        val currentPlayer = players.firstOrNull { it.isRolling }
        if (currentPlayer != null && canPass.value == true) {
            updateFromGameHandler(GameHandler.pass(players, currentPlayer))
        }
    }

    /**
     * @since notifyChange update LiveData with the same value for sending this data to LiveData listeners
     */
    private fun <T> MutableLiveData<List<T>>.notifyChange() {
        this.value = this.value
    }

    /**
     * @since updateFromGameHandler() - update the UI of the fragment_game.xml
     */
    private  fun updateFromGameHandler(result: TurnResult) {
        if (result.currentPlayer != null) {
            currentPlayer.value?.addPennies(result.coinChangeCount ?: 0)
            currentPlayer.value = result.currentPlayer
            this.players.forEach { player ->
                player.isRolling = result.currentPlayer == player
            }
        }

        if (result.lastRoll != null) {
            slots.value?.let { currentSlots ->
                updateSlots(result, currentSlots, result.lastRoll)
            }
        }

        currentTurnText.value = generateTurnText(result)
        currentStandingsText.value = generateCurrentStandings(this.players)

        canRoll.value = result.canRoll
        canPass.value = result.canPass

        if (!result.isGameOver && result.currentPlayer?.isHuman == false) {
            canPass.value = false
            canRoll.value = false

            //AI's turn to play
            playAITurn()
        }
    }

    /**
     * @since updateSlots() - update slot in fragment_game.xml
     */
    private fun updateSlots(result: TurnResult, currentSlots: List<Slot>, lastRoll: Int) {
        if (result.clearSlots) currentSlots.clear()

        currentSlots.firstOrNull { it.lastRolled }?.apply { lastRolled = false }

        currentSlots.getOrNull(lastRoll - 1)?.also { slot ->
            if (!result.clearSlots && slot.canBeFilled) slot.isFilled =  true

            slot.lastRolled = true
        }

        slots.notifyChange()
    }

    /**
     * @since generateCurrentStandings() - converts information about Players to String
     * and update the game state in the fragment_game.xml
     */
    private fun generateCurrentStandings(players: List<Player>,
                                         headerText: String = "Current Standings:") =

        //joinToString() - Creates a string from all the elements separated using separator and using the given prefix and postfix if supplied
        players.sortedBy { it.pennies }.joinToString(separator = "\n", prefix = "$headerText\n") {
            "\t${it.playerName} - ${it.pennies} pennies"
        }

    /**
     * @since generateTurnText() - converts information about Players to String
     * and update the game history in the fragment_game.xml
     */
    private fun generateTurnText(result: TurnResult): String {
        if (clearText) currentTurnText.value = ""
        clearText = result.turnEnd != null

        val currentText = currentTurnText.value ?: ""
        val currentPlayerName = result.currentPlayer?.playerName ?: "???"

        return when {

            //TurnRest - based logic and text
            result.isGameOver -> //Game's over, let's get a summary
                """|Game over!
                    |$currentPlayerName is winner!
                    |
                    |${generateCurrentStandings(this.players, "Final Scores:\n")}
                    |}}
                """.trimMargin()


            result.turnEnd == TurnEnd.Bust -> "$currentPlayerName busted, got some pennies"
            result.turnEnd == TurnEnd.Pass -> "$currentPlayerName passed"

            result.lastRoll != null -> //Roll test
                //"""___""" - текст без обработки
                // | - указатель начала строки
                "$currentText\n$currentPlayerName rolled a ${result.lastRoll}."

            else -> ""
        }
    }

    /*
    private fun generateTurnText(result: TurnResult): String {
//        if (clearText) currentTurnText.value = ""
//        clearText = result.turnEnd != null

//        val currentText = currentTurnText.value ?: ""
        val currentPlayerName = result.currentPlayer?.playerName ?: "???"

        val currentText =  when {

            //TurnRest - based logic and text
            result.isGameOver -> //Game's over, let's get a summary
                """|Game over!
                    |$currentPlayerName is winner!
                    |
                    |${generateCurrentStandings(this.players, "Final Scores:\n")}
                    |}}
                """.trimMargin()


            result.turnEnd == TurnEnd.Bust -> "\n${result.previousPlayer?.playerName} busted, got some pennies\n"
            result.turnEnd == TurnEnd.Pass -> "\n${result.previousPlayer?.playerName} passed\n"

            result.lastRoll != null -> //Roll test
                //"""___""" - текст без обработки
                // | - указатель начала строки
                "\n$currentPlayerName rolled a ${result.lastRoll}."

            else -> ""
        }
        return currentText
    }
     */

    /**
     * @since playAITurn() - AI player make a turn (GameViewModel)
     */
    private fun playAITurn() {
        viewModelScope.launch {
            delay(1000)
            slots.value?.let { currentSlot ->
                val currentPlayer = players.firstOrNull { it.isRolling }

                if (currentPlayer != null && !currentPlayer.isHuman) {
                    GameHandler.playAITurn(
                        players,
                        currentPlayer,
                        currentSlot,
                        canPass.value == true)?.let { result ->
                        updateFromGameHandler(result)
                    }
                }
            }
        }
    }
}
