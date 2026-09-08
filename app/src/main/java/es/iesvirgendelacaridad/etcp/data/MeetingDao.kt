package es.iesvirgendelacaridad.etcp.data
import androidx.room.*
import kotlinx.coroutines.flow.Flow
@Dao interface MeetingDao{@Query("SELECT * FROM meetings ORDER BY createdAt DESC") fun observeAll():Flow<List<Meeting>>;@Query("SELECT * FROM meetings WHERE id=:id") suspend fun get(id:Long):Meeting?;@Insert suspend fun insert(meeting:Meeting):Long;@Update suspend fun update(meeting:Meeting);@Delete suspend fun delete(meeting:Meeting)}
