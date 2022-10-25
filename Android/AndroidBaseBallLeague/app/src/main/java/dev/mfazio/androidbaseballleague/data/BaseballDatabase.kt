package dev.mfazio.androidbaseballleague.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.mfazio.androidbaseballleague.standings.TeamStanding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Database(entities = [TeamStanding::class], exportSchema = false, version = 1)
@TypeConverters(Converters::class)
abstract class BaseballDatabase : RoomDatabase() {
    abstract fun baseballDao(): BaseBallDao

    companion object {

        //Marks the JVM backing field of the annotated property as volatile, meaning
        // that writes to this field are immediately made visible to other threads.
        @Volatile
        private var Instance: BaseballDatabase? = null

        /**
         * @return a database's exemplar
         */
        fun getDatabase(context: Context, scope: CoroutineScope): BaseballDatabase =

            //synchronized() - Выполняет данный функциональный блок, удерживая монитор блокировки данного объекта.
            Instance ?: synchronized(this) {

                //Room.databaseBuilder() - Creates a RoomDatabase.Builder for a persistent database.
                /**
                 * @param context app's context -
                 * @param BaseballDatabase::class.java database's class
                 * @param BaseballDatabase database's name
                 */
                val instance = Room.databaseBuilder(
                    context,
                    BaseballDatabase::class.java,
                    "BaseballDatabase"

                    //add data at creating database
                ).addCallback(object: RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        scope.launch {
                            Instance?.baseballDao()?.insertStandings(TeamStanding.mockTeamStandings)
                        }
                    }
                }).build()

                Instance = instance

                instance
            }
    }
}