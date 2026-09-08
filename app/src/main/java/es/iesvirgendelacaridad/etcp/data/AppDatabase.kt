package es.iesvirgendelacaridad.etcp.data
import android.content.Context
import androidx.room.*
@Database(entities=[Meeting::class],version=1,exportSchema=false) abstract class AppDatabase:RoomDatabase(){abstract fun meetingDao():MeetingDao;companion object{@Volatile private var INSTANCE:AppDatabase?=null;fun get(context:Context):AppDatabase=INSTANCE?:synchronized(this){INSTANCE?:Room.databaseBuilder(context.applicationContext,AppDatabase::class.java,"etcp.db").build().also{INSTANCE=it}}}}
