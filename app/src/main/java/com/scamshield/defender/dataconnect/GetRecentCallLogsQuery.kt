
@file:Suppress(
  "KotlinRedundantDiagnosticSuppress",
  "LocalVariableName",
  "MayBeConstant",
  "RedundantVisibilityModifier",
  "RedundantCompanionReference",
  "RemoveEmptyClassBody",
  "SpellCheckingInspection",
  "LocalVariableName",
  "unused",
)

package com.scamshield.defender.dataconnect


import kotlinx.coroutines.flow.filterNotNull as _flow_filterNotNull
import kotlinx.coroutines.flow.map as _flow_map


public interface GetRecentCallLogsQuery :
    com.google.firebase.dataconnect.generated.GeneratedQuery<
      DefaultConnector,
      GetRecentCallLogsQuery.Data,
      GetRecentCallLogsQuery.Variables
    >
{
  
    @kotlinx.serialization.Serializable
  public data class Variables(
  
    val userId: String
  ) {
    
    
  }
  

  
    @kotlinx.serialization.Serializable
  public data class Data(
  
    val callLogs: List<CallLogsItem>
  ) {
    
      
        @kotlinx.serialization.Serializable
  public data class CallLogsItem(
  
    val id: @kotlinx.serialization.Serializable(with = com.google.firebase.dataconnect.serializers.UUIDSerializer::class) java.util.UUID,
    val userId: String,
    val phoneNumber: String,
    val callDurationSeconds: Int,
    val scamScore: Double,
    val isScam: Boolean,
    val deepfakeScore: Double,
    val analyzedAt: com.google.firebase.dataconnect.LocalDate
  ) {
    
    
  }
      
    
    
  }
  

  public companion object {
    public val operationName: String = "GetRecentCallLogs"

    public val dataDeserializer: kotlinx.serialization.DeserializationStrategy<Data> =
      kotlinx.serialization.serializer()

    public val variablesSerializer: kotlinx.serialization.SerializationStrategy<Variables> =
      kotlinx.serialization.serializer()
  }
}

public fun GetRecentCallLogsQuery.ref(
  
    userId: String,

  
  
): com.google.firebase.dataconnect.QueryRef<
    GetRecentCallLogsQuery.Data,
    GetRecentCallLogsQuery.Variables
  > =
  ref(
    
      GetRecentCallLogsQuery.Variables(
        userId=userId,
  
      )
    
  )

public suspend fun GetRecentCallLogsQuery.execute(

  
    
      userId: String,

  

  ): com.google.firebase.dataconnect.QueryResult<
    GetRecentCallLogsQuery.Data,
    GetRecentCallLogsQuery.Variables
  > =
  ref(
    
      userId=userId,
  
    
  ).execute()


  public fun GetRecentCallLogsQuery.flow(
    
      userId: String,

  
    
    ): kotlinx.coroutines.flow.Flow<GetRecentCallLogsQuery.Data> =
    ref(
        
          userId=userId,
  
        
      ).subscribe()
      .flow
      ._flow_map { querySubscriptionResult -> querySubscriptionResult.result.getOrNull() }
      ._flow_filterNotNull()
      ._flow_map { it.data }

