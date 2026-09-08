package es.iesvirgendelacaridad.etcp.data
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName="meetings") data class Meeting(@PrimaryKey(autoGenerate=true) val id:Long=0,val title:String,val meetingDate:String,val audioUri:String,val transcript:String="",val summaryJson:String="",val createdAt:Long=System.currentTimeMillis())
